package com.stenograf.app.asr

import android.content.Context
import android.net.Uri
import com.stenograf.app.i18n.AppLocaleManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException

enum class ModelDownloadPhase {
    IDLE,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    ERROR
}

data class ModelDownloadProgress(
    val modelId: String,
    val phase: ModelDownloadPhase,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = -1L,
    val errorMessage: String? = null
) {
    val fraction: Float
        get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

data class ModelInstallStatus(
    val spec: TinyAsrModelSpec,
    val isInstalled: Boolean,
    val sizeBytes: Long,
    val downloadProgress: ModelDownloadProgress? = null,
    val updateAvailable: Boolean = false,
    val installedFormat: String? = null,
    val isIncomplete: Boolean = false
) {
    val isPaused: Boolean
        get() = downloadProgress?.phase == ModelDownloadPhase.PAUSED
}

data class ModelLease(
    val modelId: String,
    val handle: Long,
    val langs: List<String>,
    val chunkMs: Int
)

internal data class OnnxModelLease(
    val modelId: String,
    val path: File
)

internal data class GigaAmV4OnlineLease(
    val modelId: String,
    val handle: Long
)

internal data class GigaAmV4OfflineLease(
    val modelId: String,
    val handle: Long
)

private data class LoadedModelEntry(
    val modelId: String,
    val handle: Long,
    val threads: Int,
    val langs: List<String>,
    val chunkMs: Int,
    var refCount: Int = 0
)

private data class LoadedGigaV4Entry(
    val handle: Long,
    val threads: Int,
    var refCount: Int = 0
)

@Serializable
private data class DlMetaEntry(
    val totalBytes: Long = 0L,
    val etag: String? = null
)

@Serializable
private data class InstalledMetaEntry(
    val sizeBytes: Long = 0L,
    val versionTag: String = "",
    val installedAt: Long = 0L,
    val etag: String? = null,
    val tzfVersion: String? = null
)

private val metaJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private val metaMapSerializer = MapSerializer(String.serializer(), InstalledMetaEntry.serializer())

class ModelManager private constructor(
    private val appContext: Context?,
    private val modelsDir: File,
    private val downloadsDir: File,
    private val assetOpener: (String) -> InputStream
) {
    constructor(context: Context) : this(
        appContext = context.applicationContext,
        modelsDir = File(context.filesDir, "models").apply { mkdirs() },
        downloadsDir = File(context.cacheDir, "model_downloads").apply { mkdirs() },
        assetOpener = { path -> context.assets.open(path) }
    )

    internal constructor(
        modelsDir: File,
        downloadsDir: File,
        assetOpener: (String) -> InputStream = { throw IllegalStateException("Assets unavailable") }
    ) : this(
        appContext = null,
        modelsDir = modelsDir.apply { mkdirs() },
        downloadsDir = downloadsDir.apply { mkdirs() },
        assetOpener = assetOpener
    )

    private val metaFile: File = File(modelsDir, "model_meta.json")
    internal var bundleUrlResolver: (BundleFileSpec) -> String = { it.url }
    private val loadMutex = Mutex()
    private val vadInstallMutex = Mutex()
    private val metaLock = Any()

    private val loadedEntries = ConcurrentHashMap<String, LoadedModelEntry>()
    private val onnxUsageCounts = ConcurrentHashMap<String, Int>()
    private var loadedGigaV4Online: LoadedGigaV4Entry? = null
    private var loadedGigaV4Offline: LoadedGigaV4Entry? = null

    // 0 = none, 1 = pause requested, 2 = cancel requested
    private val activeDownloads = ConcurrentHashMap.newKeySet<String>()
    private val controlFlags = ConcurrentHashMap<String, AtomicInteger>()

    private val _downloadStates = MutableStateFlow<Map<String, ModelDownloadProgress>>(emptyMap())
    val downloadStates: StateFlow<Map<String, ModelDownloadProgress>> = _downloadStates.asStateFlow()

    private val _updatesAvailable = MutableStateFlow<Set<String>>(emptySet())
    val updatesAvailable: StateFlow<Set<String>> = _updatesAvailable.asStateFlow()

    init {
        modelsDir.mkdirs()
        downloadsDir.mkdirs()
        restorePausedDownloadStates()
    }

    fun getVadFile(): File = File(modelsDir, TinyAsrCatalog.VAD_UNPACKED_NAME)

    fun getModelFile(modelId: String): File {
        val spec = TinyAsrCatalog.findModel(modelId)
        return File(modelsDir, if (spec.isMultiFileBundle) spec.id else spec.unpackedName)
    }

    fun getBundleDir(modelId: String): File = File(modelsDir, modelId)

    private fun bundleMarkerFile(dir: File): File = File(dir, ".verified")

    private fun bundleDownloadDir(modelId: String): File = File(downloadsDir, modelId)

    private fun recursiveLength(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf(::recursiveLength) ?: 0L
    }

    fun isModelInstalled(modelId: String): Boolean {
        val spec = TinyAsrCatalog.findModel(modelId)
        if (spec.isMultiFileBundle) {
            val dir = getBundleDir(modelId)
            return dir.isDirectory &&
                bundleMarkerFile(dir).isFile &&
                spec.bundleFiles.all { f ->
                    val target = File(dir, f.relativePath)
                    target.isFile && target.length() == f.bytes
                }
        }
        val file = getModelFile(modelId)
        val expected = spec.onnxDownload?.bytes
        return file.isFile && (expected?.let { file.length() == it } ?: (file.length() > 1_000_000L))
    }

    fun getModelsTotalBytes(): Long {
        return modelsDir.listFiles()
            ?.filter { !it.name.endsWith(".tmp") && !it.name.endsWith(".import") }
            ?.sumOf(::recursiveLength) ?: 0L
    }

    fun getDownloadsCacheBytes(): Long {
        return downloadsDir.listFiles()?.sumOf(::recursiveLength) ?: 0L
    }

    fun getAllModelStatuses(): List<ModelInstallStatus> {
        val dls = _downloadStates.value
        val updates = _updatesAvailable.value
        val meta = readMetaJson()
        return TinyAsrCatalog.DOWNLOAD_MODELS.map { spec ->
            val f = getModelFile(spec.id)
            val installed = isModelInstalled(spec.id)
            val mObj = meta[spec.id]
            val fmt = mObj?.tzfVersion?.takeIf { it.isNotBlank() }
            val dl = dls[spec.id]
            val activeDownload = dl?.phase == ModelDownloadPhase.DOWNLOADING || dl?.phase == ModelDownloadPhase.VERIFYING
            val incomplete = !installed && !activeDownload && (
                dl?.phase == ModelDownloadPhase.PAUSED ||
                    dl?.phase == ModelDownloadPhase.ERROR ||
                    (spec.isMultiFileBundle && (getBundleDir(spec.id).exists() || recursiveLength(bundleDownloadDir(spec.id)) > 0L)) ||
                    (!spec.isMultiFileBundle && File(downloadsDir, "${spec.fileName}.part").let { it.exists() && it.length() > 0L })
                )
            ModelInstallStatus(
                spec = spec,
                isInstalled = installed,
                sizeBytes = if (installed) recursiveLength(f) else 0L,
                downloadProgress = dl,
                updateAvailable = updates.contains(spec.id),
                installedFormat = fmt,
                isIncomplete = incomplete
            )
        }
    }

    fun cleanupOrphanedDownloads() {
        modelsDir.listFiles()?.forEach { f ->
            if ((f.name.endsWith(".tmp") || f.name.endsWith(".import") || f.name.startsWith("ggml-")) &&
                System.currentTimeMillis() - f.lastModified() > 12L * 3600L * 1000L
            ) {
                f.deleteRecursively()
            }
        }
        val activeIds = _downloadStates.value
            .filterValues {
                it.phase == ModelDownloadPhase.DOWNLOADING ||
                    it.phase == ModelDownloadPhase.PAUSED ||
                    it.phase == ModelDownloadPhase.VERIFYING ||
                    it.phase == ModelDownloadPhase.ERROR
            }
            .keys
        downloadsDir.listFiles()?.forEach { f ->
            val belongsToActive = activeIds.any { id ->
                val spec = TinyAsrCatalog.findModel(id)
                f.name == spec.id || f.name.startsWith(spec.fileName)
            }
            if (!belongsToActive || f.name.startsWith("ggml-")) f.deleteRecursively()
        }
    }

    suspend fun ensureVadInstalled(): File = vadInstallMutex.withLock {
        withContext(Dispatchers.IO) {
            val vadFile = getVadFile()
            if (!vadFile.isFile || vadFile.length() <= 100_000L) {
                unpackAssetGz("models/${TinyAsrCatalog.VAD_ASSET_NAME}", vadFile)
            }
            vadFile
        }
    }

    suspend fun ensureModelInstalled(modelId: String): File = withContext(Dispatchers.IO) {
        val spec = TinyAsrCatalog.findModel(modelId)
        val target = getModelFile(modelId)
        if (isModelInstalled(modelId)) return@withContext target
        val strings = AppLocaleManager.currentStrings()
        if (spec.bundledInApk) {
            unpackAssetGz("models/${spec.assetName}", target)
            saveModelMeta(spec.id, target.length(), etag = null, versionTag = spec.versionTag, tzfVersion = readTzfMagicVersion(target))
            return@withContext target
        }
        throw IllegalStateException("${strings.modelRequiredInstallPrompt}: ${spec.localizedTitle(strings)}")
    }

    internal suspend fun acquireOnnxLease(modelId: String): OnnxModelLease = loadMutex.withLock {
        withContext(Dispatchers.IO) {
            val spec = TinyAsrCatalog.findModel(modelId)
            require(spec.isOnnxModel)
            val path = ensureModelInstalled(modelId)
            onnxUsageCounts[modelId] = (onnxUsageCounts[modelId] ?: 0) + 1
            OnnxModelLease(modelId, path)
        }
    }

    internal suspend fun releaseOnnxLease(lease: OnnxModelLease?) = loadMutex.withLock {
        if (lease == null) return@withLock
        val next = (onnxUsageCounts[lease.modelId] ?: 0) - 1
        if (next <= 0) onnxUsageCounts.remove(lease.modelId) else onnxUsageCounts[lease.modelId] = next
    }

    suspend fun acquireModelLease(modelId: String, requestedThreads: Int): ModelLease = loadMutex.withLock {
        withContext(Dispatchers.IO) {
            require(!TinyAsrCatalog.findModel(modelId).isOnnxModel)
            val existing = loadedEntries[modelId]
            if (existing != null && existing.handle != 0L) {
                if (existing.threads == requestedThreads || existing.refCount > 0) {
                    existing.refCount += 1
                    return@withContext ModelLease(modelId, existing.handle, existing.langs, existing.chunkMs)
                } else {
                    TinyAsrNative.nativeFreeModels(existing.handle)
                    loadedEntries.remove(modelId)
                }
            }

            // Free any idle (unleased) models from other IDs/runtimes to keep RAM tight
            freeIdleModelsLocked(keepTinyId = modelId)

            val vadFile = ensureVadInstalled()
            val asrFile = ensureModelInstalled(modelId)
            val strings = AppLocaleManager.currentStrings()

            val errBuf = ByteArray(256)
            val handle = TinyAsrNative.nativeLoadModels(
                asrFile.absolutePath,
                vadFile.absolutePath,
                requestedThreads,
                errBuf
            )
            if (handle == 0L) {
                val errText = errBuf.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
                    .ifBlank { strings.asrSessionError }
                throw IllegalStateException(errText)
            }

            val langs = TinyAsrNative.nativeGetLangs(handle).toList()
            val chunkMs = TinyAsrNative.nativeChunkMs(handle)
            val entry = LoadedModelEntry(
                modelId = modelId,
                handle = handle,
                threads = requestedThreads,
                langs = langs,
                chunkMs = chunkMs,
                refCount = 1
            )
            loadedEntries[modelId] = entry
            ModelLease(modelId, handle, langs, chunkMs)
        }
    }

    suspend fun releaseModelLease(lease: ModelLease?) = loadMutex.withLock {
        if (lease == null) return@withLock
        val entry = loadedEntries[lease.modelId] ?: return@withLock
        entry.refCount = (entry.refCount - 1).coerceAtLeast(0)
    }

    private fun freeIdleModelsLocked(
        keepTinyId: String? = null,
        keepGigaV4Online: Boolean = false,
        keepGigaV4Offline: Boolean = false
    ) {
        val idleKeys = loadedEntries.entries.filter { it.value.refCount <= 0 && it.key != keepTinyId }.map { it.key }
        for (k in idleKeys) {
            loadedEntries.remove(k)?.let { entry ->
                if (entry.handle != 0L) TinyAsrNative.nativeFreeModels(entry.handle)
            }
        }
        if (!keepGigaV4Online) {
            loadedGigaV4Online?.takeIf { it.refCount <= 0 }?.let { entry ->
                if (entry.handle != 0L) GigaAmV4Native.nativeFreeOnline(entry.handle)
                loadedGigaV4Online = null
            }
        }
        if (!keepGigaV4Offline) {
            loadedGigaV4Offline?.takeIf { it.refCount <= 0 }?.let { entry ->
                if (entry.handle != 0L) GigaAmV4Native.nativeFreeOffline(entry.handle)
                loadedGigaV4Offline = null
            }
        }
    }

    internal suspend fun acquireGigaV4OnlineLease(requestedThreads: Int): GigaAmV4OnlineLease = loadMutex.withLock {
        withContext(Dispatchers.IO) {
            val existing = loadedGigaV4Online
            if (existing != null && existing.handle != 0L) {
                if (existing.threads == requestedThreads || existing.refCount > 0) {
                    existing.refCount += 1
                    onnxUsageCounts[TinyAsrCatalog.GIGAAM_V4] = (onnxUsageCounts[TinyAsrCatalog.GIGAAM_V4] ?: 0) + 1
                    return@withContext GigaAmV4OnlineLease(TinyAsrCatalog.GIGAAM_V4, existing.handle)
                } else {
                    GigaAmV4Native.nativeFreeOnline(existing.handle)
                    loadedGigaV4Online = null
                }
            }
            freeIdleModelsLocked(keepGigaV4Online = true)
            val bundleDir = ensureModelInstalled(TinyAsrCatalog.GIGAAM_V4)
            val strings = AppLocaleManager.currentStrings()
            val errBuf = ByteArray(512)
            val handle = GigaAmV4Native.nativeLoadOnline(bundleDir.absolutePath, requestedThreads, errBuf)
            if (handle == 0L) {
                throw IllegalStateException(GigaAmV4JsonParser.decodeError(errBuf, strings.asrSessionError))
            }
            loadedGigaV4Online = LoadedGigaV4Entry(handle = handle, threads = requestedThreads, refCount = 1)
            onnxUsageCounts[TinyAsrCatalog.GIGAAM_V4] = (onnxUsageCounts[TinyAsrCatalog.GIGAAM_V4] ?: 0) + 1
            GigaAmV4OnlineLease(TinyAsrCatalog.GIGAAM_V4, handle)
        }
    }

    internal suspend fun releaseGigaV4OnlineLease(lease: GigaAmV4OnlineLease?) = loadMutex.withLock {
        if (lease == null) return@withLock
        loadedGigaV4Online?.let { it.refCount = (it.refCount - 1).coerceAtLeast(0) }
        val next = (onnxUsageCounts[lease.modelId] ?: 0) - 1
        if (next <= 0) onnxUsageCounts.remove(lease.modelId) else onnxUsageCounts[lease.modelId] = next
    }

    internal suspend fun acquireGigaV4OfflineLease(requestedThreads: Int): GigaAmV4OfflineLease = loadMutex.withLock {
        withContext(Dispatchers.IO) {
            val existing = loadedGigaV4Offline
            if (existing != null && existing.handle != 0L) {
                if (existing.threads == requestedThreads || existing.refCount > 0) {
                    existing.refCount += 1
                    onnxUsageCounts[TinyAsrCatalog.GIGAAM_V4] = (onnxUsageCounts[TinyAsrCatalog.GIGAAM_V4] ?: 0) + 1
                    return@withContext GigaAmV4OfflineLease(TinyAsrCatalog.GIGAAM_V4, existing.handle)
                } else {
                    GigaAmV4Native.nativeFreeOffline(existing.handle)
                    loadedGigaV4Offline = null
                }
            }
            freeIdleModelsLocked(keepGigaV4Offline = true)
            val bundleDir = ensureModelInstalled(TinyAsrCatalog.GIGAAM_V4)
            val strings = AppLocaleManager.currentStrings()
            val errBuf = ByteArray(512)
            val handle = GigaAmV4Native.nativeLoadOffline(bundleDir.absolutePath, requestedThreads, errBuf)
            if (handle == 0L) {
                throw IllegalStateException(GigaAmV4JsonParser.decodeError(errBuf, strings.asrSessionError))
            }
            loadedGigaV4Offline = LoadedGigaV4Entry(handle = handle, threads = requestedThreads, refCount = 1)
            onnxUsageCounts[TinyAsrCatalog.GIGAAM_V4] = (onnxUsageCounts[TinyAsrCatalog.GIGAAM_V4] ?: 0) + 1
            GigaAmV4OfflineLease(TinyAsrCatalog.GIGAAM_V4, handle)
        }
    }

    internal suspend fun releaseGigaV4OfflineLease(lease: GigaAmV4OfflineLease?) = loadMutex.withLock {
        if (lease == null) return@withLock
        loadedGigaV4Offline?.let { it.refCount = (it.refCount - 1).coerceAtLeast(0) }
        val next = (onnxUsageCounts[lease.modelId] ?: 0) - 1
        if (next <= 0) onnxUsageCounts.remove(lease.modelId) else onnxUsageCounts[lease.modelId] = next
    }

    suspend fun downloadModel(modelId: String): Result<File> = withContext(Dispatchers.IO) {
        val spec = TinyAsrCatalog.findModel(modelId)
        if (spec.isMultiFileBundle) {
            return@withContext downloadBundleFiles(modelId, spec.bundleFiles)
        }
        val strings = AppLocaleManager.currentStrings()
        val target = getModelFile(modelId)
        val partFile = File(downloadsDir, "${spec.fileName}.part")
        val dlMetaFile = File(downloadsDir, "${spec.fileName}.dlmeta")
        val tmpUnpacked = File(modelsDir, "${spec.unpackedName}.tmp")

        val flag = controlFlags.computeIfAbsent(modelId) { AtomicInteger(0) }
        val started = loadMutex.withLock {
            synchronized(flag) {
                activeDownloads.add(modelId).also { if (it) flag.set(0) }
            }
        }
        if (!started) return@withContext Result.failure(CancellationException("Download already running"))

        var conn: HttpURLConnection? = null
        val expectedFallbackBytes = spec.onnxDownload?.bytes ?: (spec.sizeMb * 1_048_576f).toLong()
        var totalBytes = readDlMeta(dlMetaFile)?.totalBytes?.takeIf { it > 0L } ?: expectedFallbackBytes
        val worker = coroutineContext

        fun checkControl() {
            worker.ensureActive()
            when (flag.get()) {
                1 -> throw PausedByUserException()
                2 -> throw CancellationException("Cancelled")
            }
        }

        try {
            checkControl()
            var existingBytes = if (partFile.exists()) partFile.length() else 0L

            updateDownloadState(
                ModelDownloadProgress(
                    modelId = modelId,
                    phase = ModelDownloadPhase.DOWNLOADING,
                    downloadedBytes = existingBytes,
                    totalBytes = totalBytes
                )
            )

            conn = (URL(spec.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "identity")
                if (existingBytes > 0L) {
                    setRequestProperty("Range", "bytes=$existingBytes-")
                    readDlMeta(dlMetaFile)?.etag?.takeUnless { it.startsWith("W/") }?.let { etag ->
                        setRequestProperty("If-Range", etag)
                    }
                }
            }

            val code = conn.responseCode
            val resumeAccepted = code == HttpURLConnection.HTTP_PARTIAL
            if (resumeAccepted &&
                conn.getHeaderField("Content-Range")?.startsWith("bytes $existingBytes-") != true
            ) {
                partFile.delete()
                dlMetaFile.delete()
                throw IllegalStateException(strings.modelCorruptError)
            }
            if (code == HttpURLConnection.HTTP_OK && existingBytes > 0L) {
                partFile.delete()
                existingBytes = 0L
            } else if (code == 416) {
                // Range not satisfiable (either file completed already or stale part)
                if (existingBytes < expectedFallbackBytes / 2L) {
                    partFile.delete()
                    throw IllegalStateException("HTTP 416")
                }
            } else if (!resumeAccepted && code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }

            val etag = conn.getHeaderField("ETag")?.trim()
            val contentLength = conn.contentLengthLong
            totalBytes = when {
                code == 416 -> totalBytes
                resumeAccepted && contentLength > 0L -> existingBytes + contentLength
                contentLength > 0L -> contentLength
                else -> totalBytes
            }
            saveDlMeta(dlMetaFile, totalBytes, etag)

            if (code != 416) {
                conn.inputStream.use { input ->
                    RandomAccessFile(partFile, "rw").use { raf ->
                        raf.seek(existingBytes)
                        val buf = ByteArray(32 * 1024)
                        var downloaded = existingBytes
                        var lastUiUpdateMs = System.currentTimeMillis()
                        var bytesSinceTick = 0L
                        var smoothedSpeed = 0.0

                        while (true) {
                            checkControl()
                            val read = input.read(buf)
                            if (read < 0) break
                            if (read == 0) continue
                            raf.write(buf, 0, read)
                            downloaded += read
                            if (spec.onnxDownload != null && downloaded > spec.onnxDownload.bytes) {
                                throw IllegalStateException(strings.modelCorruptError)
                            }
                            bytesSinceTick += read

                            val now = System.currentTimeMillis()
                            val elapsedMs = now - lastUiUpdateMs
                            if (elapsedMs >= 250L) {
                                val instantSpeed = (bytesSinceTick * 1000.0) / elapsedMs
                                smoothedSpeed = if (smoothedSpeed <= 0.0) instantSpeed else (smoothedSpeed * 0.6 + instantSpeed * 0.4)
                                val speedLong = smoothedSpeed.toLong().coerceAtLeast(1L)
                                val remaining = (totalBytes - downloaded).coerceAtLeast(0L)
                                val eta = remaining / speedLong
                                updateDownloadState(
                                    ModelDownloadProgress(
                                        modelId = modelId,
                                        phase = ModelDownloadPhase.DOWNLOADING,
                                        downloadedBytes = downloaded,
                                        totalBytes = totalBytes,
                                        speedBytesPerSec = speedLong,
                                        etaSeconds = eta
                                    )
                                )
                                lastUiUpdateMs = now
                                bytesSinceTick = 0L
                            }
                        }
                    }
                }
            }

            checkControl()

            // Verify pinned ONNX bytes or validate the unpacked TinyASR model.
            updateDownloadState(
                ModelDownloadProgress(
                    modelId = modelId,
                    phase = ModelDownloadPhase.VERIFYING,
                    downloadedBytes = totalBytes,
                    totalBytes = totalBytes
                )
            )

            val onnx = spec.onnxDownload
            if (onnx != null) {
                if (partFile.length() != onnx.bytes || !matchesSha256(partFile, onnx.sha256, ::checkControl)
                ) {
                    partFile.delete()
                    dlMetaFile.delete()
                    throw IllegalStateException(strings.modelCorruptError)
                }
                loadMutex.withLock {
                    checkControl()
                    check((onnxUsageCounts[modelId] ?: 0) == 0) { strings.modelCannotDeleteActive }
                    Files.move(partFile.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    dlMetaFile.delete()
                    removeDownloadState(modelId)
                }
                return@withContext Result.success(target)
            }

            try {
                GZIPInputStream(BufferedInputStream(FileInputStream(partFile), 64 * 1024)).use { gz ->
                    copyStreamToFile(gz, tmpUnpacked, ::checkControl)
                }
            } catch (failure: java.io.IOException) {
                if (failure !is ZipException && failure !is EOFException) throw failure
                partFile.delete()
                dlMetaFile.delete()
                throw IllegalStateException(strings.modelCorruptError, failure)
            }

            val tzfVersion = readTzfMagicVersion(tmpUnpacked)
                ?: run {
                    partFile.delete()
                    dlMetaFile.delete()
                    throw IllegalStateException(strings.modelCorruptError)
                }

            val vad = ensureVadInstalled()
            val err = ByteArray(256)
            val testHandle = TinyAsrNative.nativeLoadModels(tmpUnpacked.absolutePath, vad.absolutePath, 1, err)
            if (testHandle == 0L) {
                val msg = err.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
                partFile.delete()
                dlMetaFile.delete()
                throw IllegalStateException(msg.ifBlank { strings.modelCorruptError })
            }
            TinyAsrNative.nativeFreeModels(testHandle)

            loadMutex.withLock {
                checkControl()
                publishTinyModel(modelId, tmpUnpacked, target)
                partFile.delete()
                dlMetaFile.delete()
                saveModelMeta(spec.id, target.length(), etag, spec.versionTag, tzfVersion)
                _updatesAvailable.update { it - spec.id }
                removeDownloadState(modelId)
            }
            Result.success(target)
        } catch (paused: PausedByUserException) {
            tmpUnpacked.delete()
            savePausedDownload(modelId, partFile.length(), totalBytes)
            Result.failure(paused)
        } catch (ce: CancellationException) {
            tmpUnpacked.delete()
            if (flag.get() == 2) {
                partFile.delete()
                dlMetaFile.delete()
                removeDownloadState(modelId)
            } else {
                savePausedDownload(modelId, partFile.length(), totalBytes)
                throw ce
            }
            Result.failure(ce)
        } catch (t: Throwable) {
            tmpUnpacked.delete()
            if (flag.get() == 2) {
                partFile.delete()
                dlMetaFile.delete()
                removeDownloadState(modelId)
                return@withContext Result.failure(CancellationException("Cancelled"))
            }
            if (spec.onnxDownload?.let { partFile.length() > it.bytes } == true) {
                partFile.delete()
                dlMetaFile.delete()
            }
            // Keep .part on transient network errors so user can resume unless corrupt
            val msg = t.message?.takeIf { it.isNotBlank() } ?: strings.modelDownloadError
            val currentBytes = if (partFile.exists()) partFile.length() else 0L
            updateDownloadState(
                ModelDownloadProgress(
                    modelId = modelId,
                    phase = if (currentBytes > 0L) ModelDownloadPhase.PAUSED else ModelDownloadPhase.ERROR,
                    downloadedBytes = currentBytes,
                    totalBytes = totalBytes,
                    errorMessage = msg
                )
            )
            Result.failure(t)
        } finally {
            conn?.disconnect()
            activeDownloads.remove(modelId)
        }
    }

    internal suspend fun downloadBundleFiles(
        modelId: String,
        files: List<BundleFileSpec>
    ): Result<File> = withContext(Dispatchers.IO) {
        require(files.isNotEmpty())
        val strings = AppLocaleManager.currentStrings()
        val bundleDir = getBundleDir(modelId)
        val stageDir = bundleDownloadDir(modelId)
        val tmpInstallDir = File(modelsDir, "$modelId.tmp")
        val totalBundleBytes = files.sumOf { it.bytes }

        val flag = controlFlags.computeIfAbsent(modelId) { AtomicInteger(0) }
        val started = loadMutex.withLock {
            synchronized(flag) {
                activeDownloads.add(modelId).also { if (it) flag.set(0) }
            }
        }
        if (!started) return@withContext Result.failure(CancellationException("Download already running"))

        fun sumStagedBytes(): Long {
            var sum = 0L
            for (item in files) {
                val staged = File(stageDir, item.relativePath)
                val part = File(stageDir, "${item.relativePath}.part")
                if (staged.isFile && staged.length() == item.bytes) {
                    sum += item.bytes
                } else if (part.isFile) {
                    sum += part.length().coerceAtMost(item.bytes)
                }
            }
            return sum.coerceAtMost(totalBundleBytes)
        }

        fun checkControl(currentBytes: Long) {
            when (flag.get()) {
                1 -> {
                    updateDownloadState(
                        ModelDownloadProgress(
                            modelId = modelId,
                            phase = ModelDownloadPhase.PAUSED,
                            downloadedBytes = currentBytes.coerceAtMost(totalBundleBytes),
                            totalBytes = totalBundleBytes
                        )
                    )
                    throw PausedByUserException()
                }
                2 -> throw CancellationException("Cancelled")
            }
        }

        var activeConn: HttpURLConnection? = null
        try {
            stageDir.mkdirs()
            val initialBytes = sumStagedBytes()
            updateDownloadState(
                ModelDownloadProgress(
                    modelId = modelId,
                    phase = ModelDownloadPhase.DOWNLOADING,
                    downloadedBytes = initialBytes,
                    totalBytes = totalBundleBytes
                )
            )

            var completedPrefixBytes = 0L
            var lastUiUpdateMs = System.currentTimeMillis()
            var bytesSinceTick = 0L
            var smoothedSpeed = 0.0

            for (item in files) {
                coroutineContext.ensureActive()
                checkControl(completedPrefixBytes)
                val staged = File(stageDir, item.relativePath)
                val part = File(stageDir, "${item.relativePath}.part")
                staged.parentFile?.mkdirs()

                if (staged.isFile) {
                    val validExisting = staged.length() == item.bytes &&
                        matchesSha256(staged, item.sha256) {
                            coroutineContext.ensureActive()
                            checkControl(completedPrefixBytes + item.bytes)
                        }
                    if (validExisting) {
                        completedPrefixBytes += item.bytes
                        continue
                    }
                    staged.delete()
                }

                var existingBytes = if (part.isFile) part.length() else 0L
                if (existingBytes > item.bytes) {
                    part.delete()
                    existingBytes = 0L
                }

                if (existingBytes < item.bytes) {
                    val conn = (URL(bundleUrlResolver(item)).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20_000
                        readTimeout = 30_000
                        instanceFollowRedirects = true
                        setRequestProperty("Accept-Encoding", "identity")
                        if (existingBytes > 0L) {
                            setRequestProperty("Range", "bytes=$existingBytes-")
                        }
                    }
                    activeConn = conn
                    try {
                        val code = conn.responseCode
                        val resumeAccepted = code == HttpURLConnection.HTTP_PARTIAL
                        if (resumeAccepted &&
                            conn.getHeaderField("Content-Range")?.startsWith("bytes $existingBytes-") != true
                        ) {
                            part.delete()
                            throw IllegalStateException(strings.modelCorruptError)
                        }
                        if (code == HttpURLConnection.HTTP_OK && existingBytes > 0L) {
                            part.delete()
                            existingBytes = 0L
                        } else if (code == 416) {
                            if (existingBytes != item.bytes) {
                                part.delete()
                                throw IllegalStateException("HTTP 416")
                            }
                        } else if (!resumeAccepted && code !in 200..299) {
                            throw IllegalStateException("HTTP $code")
                        }

                        val contentLength = conn.contentLengthLong
                        if (contentLength > 0L) {
                            val expectedRemaining = if (resumeAccepted) item.bytes - existingBytes else item.bytes
                            if (contentLength != expectedRemaining) {
                                part.delete()
                                throw IllegalStateException(strings.modelCorruptError)
                            }
                        }

                        if (code != 416) {
                            conn.inputStream.use { input ->
                                RandomAccessFile(part, "rw").use { raf ->
                                    raf.seek(existingBytes)
                                    val buf = ByteArray(64 * 1024)
                                    var fileDownloaded = existingBytes
                                    while (true) {
                                        coroutineContext.ensureActive()
                                        checkControl(completedPrefixBytes + fileDownloaded)
                                        val read = input.read(buf)
                                        if (read <= 0) break
                                        raf.write(buf, 0, read)
                                        fileDownloaded += read
                                        if (fileDownloaded > item.bytes) {
                                            part.delete()
                                            throw IllegalStateException(strings.modelCorruptError)
                                        }
                                        bytesSinceTick += read
                                        val now = System.currentTimeMillis()
                                        val elapsedMs = now - lastUiUpdateMs
                                        if (elapsedMs >= 250L) {
                                            val instantSpeed = (bytesSinceTick * 1000.0) / elapsedMs
                                            smoothedSpeed = if (smoothedSpeed <= 0.0) instantSpeed
                                            else (smoothedSpeed * 0.6 + instantSpeed * 0.4)
                                            val speedLong = smoothedSpeed.toLong().coerceAtLeast(1L)
                                            val totalSoFar = (completedPrefixBytes + fileDownloaded).coerceAtMost(totalBundleBytes)
                                            val eta = (totalBundleBytes - totalSoFar).coerceAtLeast(0L) / speedLong
                                            updateDownloadState(
                                                ModelDownloadProgress(
                                                    modelId = modelId,
                                                    phase = ModelDownloadPhase.DOWNLOADING,
                                                    downloadedBytes = totalSoFar,
                                                    totalBytes = totalBundleBytes,
                                                    speedBytesPerSec = speedLong,
                                                    etaSeconds = eta
                                                )
                                            )
                                            lastUiUpdateMs = now
                                            bytesSinceTick = 0L
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        conn.disconnect()
                        activeConn = null
                    }
                }

                checkControl(completedPrefixBytes + part.length())

                if (!part.isFile || part.length() != item.bytes || !matchesSha256(part, item.sha256) {
                        coroutineContext.ensureActive()
                        checkControl(completedPrefixBytes + part.length())
                    }
                ) {
                    part.delete()
                    throw IllegalStateException(strings.modelCorruptError)
                }

                Files.move(part.toPath(), staged.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                completedPrefixBytes += item.bytes
                updateDownloadState(
                    ModelDownloadProgress(
                        modelId = modelId,
                        phase = ModelDownloadPhase.DOWNLOADING,
                        downloadedBytes = completedPrefixBytes,
                        totalBytes = totalBundleBytes,
                        speedBytesPerSec = smoothedSpeed.toLong().coerceAtLeast(0L),
                        etaSeconds = -1L
                    )
                )
            }

            checkControl(totalBundleBytes)
            updateDownloadState(
                ModelDownloadProgress(
                    modelId = modelId,
                    phase = ModelDownloadPhase.VERIFYING,
                    downloadedBytes = totalBundleBytes,
                    totalBytes = totalBundleBytes
                )
            )

            for (item in files) {
                coroutineContext.ensureActive()
                checkControl(totalBundleBytes)
                val staged = File(stageDir, item.relativePath)
                val ok = staged.isFile && staged.length() == item.bytes &&
                    matchesSha256(staged, item.sha256) {
                        coroutineContext.ensureActive()
                        checkControl(totalBundleBytes)
                    }
                if (!ok) {
                    staged.delete()
                    throw IllegalStateException(strings.modelCorruptError)
                }
            }

            loadMutex.withLock {
                coroutineContext.ensureActive()
                checkControl(totalBundleBytes)
                check((onnxUsageCounts[modelId] ?: 0) == 0) { strings.modelCannotDeleteActive }
                if (tmpInstallDir.exists()) tmpInstallDir.deleteRecursively()
                tmpInstallDir.mkdirs()
                for (item in files) {
                    val src = File(stageDir, item.relativePath)
                    val dst = File(tmpInstallDir, item.relativePath)
                    dst.parentFile?.mkdirs()
                    Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                bundleMarkerFile(tmpInstallDir).writeText(TinyAsrCatalog.GIGAAM_V4_REVISION)
                if (bundleDir.exists()) bundleDir.deleteRecursively()
                check(tmpInstallDir.renameTo(bundleDir)) { "Failed to install bundle $modelId" }
                stageDir.deleteRecursively()
                val spec = TinyAsrCatalog.findModel(modelId)
                saveModelMeta(modelId, totalBundleBytes, etag = null, versionTag = spec.versionTag, tzfVersion = null)
                removeDownloadState(modelId)
            }
            Result.success(bundleDir)
        } catch (_: PausedByUserException) {
            tmpInstallDir.deleteRecursively()
            Result.failure(CancellationException("Paused"))
        } catch (ce: CancellationException) {
            tmpInstallDir.deleteRecursively()
            if (flag.get() == 2) {
                stageDir.deleteRecursively()
                removeDownloadState(modelId)
            } else {
                savePausedDownload(modelId, sumStagedBytes(), totalBundleBytes)
                throw ce
            }
            Result.failure(ce)
        } catch (t: Throwable) {
            tmpInstallDir.deleteRecursively()
            if (flag.get() == 2) {
                stageDir.deleteRecursively()
                removeDownloadState(modelId)
                return@withContext Result.failure(CancellationException("Cancelled"))
            }
            val msg = t.message?.takeIf { it.isNotBlank() } ?: strings.modelDownloadError
            val currentBytes = sumStagedBytes()
            updateDownloadState(
                ModelDownloadProgress(
                    modelId = modelId,
                    phase = if (currentBytes > 0L) ModelDownloadPhase.PAUSED else ModelDownloadPhase.ERROR,
                    downloadedBytes = currentBytes,
                    totalBytes = totalBundleBytes,
                    errorMessage = msg
                )
            )
            Result.failure(t)
        } finally {
            activeConn?.disconnect()
            activeDownloads.remove(modelId)
        }
    }

    fun pauseDownload(modelId: String) {
        controlFlags.computeIfAbsent(modelId) { AtomicInteger(0) }.set(1)
    }

    fun cancelDownload(modelId: String) {
        val flag = controlFlags.computeIfAbsent(modelId) { AtomicInteger(0) }
        synchronized(flag) {
            flag.set(2)
            if (modelId in activeDownloads) return
            val spec = TinyAsrCatalog.findModel(modelId)
            if (spec.isMultiFileBundle) {
                bundleDownloadDir(modelId).deleteRecursively()
                File(modelsDir, "$modelId.tmp").deleteRecursively()
            } else {
                File(downloadsDir, "${spec.fileName}.part").delete()
                File(downloadsDir, "${spec.fileName}.dlmeta").delete()
            }
            removeDownloadState(modelId)
        }
    }

    suspend fun checkForModelUpdates(): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, Boolean>()
        val meta = readMetaJson()
        val updateSet = mutableSetOf<String>()
        for (spec in TinyAsrCatalog.DOWNLOAD_MODELS) {
            if (spec.isOnnxModel || !isModelInstalled(spec.id)) {
                results[spec.id] = false
                continue
            }
            val savedEtag = meta[spec.id]?.etag.orEmpty()
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(spec.downloadUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    instanceFollowRedirects = true
                }
                if (conn.responseCode in 200..299) {
                    val remoteEtag = conn.getHeaderField("ETag")?.trim().orEmpty()
                    val hasUpdate = savedEtag.isNotBlank() && remoteEtag.isNotBlank() && savedEtag != remoteEtag
                    results[spec.id] = hasUpdate
                    if (hasUpdate) updateSet += spec.id
                } else {
                    results[spec.id] = false
                }
            } catch (_: Throwable) {
                results[spec.id] = false
            } finally {
                conn?.disconnect()
            }
        }
        _updatesAvailable.value = updateSet
        results
    }

    suspend fun importModelFromUri(uri: Uri, targetModelId: String): Result<File> = withContext(Dispatchers.IO) {
        val strings = AppLocaleManager.currentStrings()
        require(!TinyAsrCatalog.findModel(targetModelId).isOnnxModel)
        val resolver = checkNotNull(appContext).contentResolver
        val target = getModelFile(targetModelId)
        val started = loadMutex.withLock { activeDownloads.add(targetModelId) }
        if (!started) {
            return@withContext Result.failure(IllegalStateException(strings.modelCannotDeleteActive))
        }
        var temporary: File? = null
        val worker = coroutineContext
        try {
            val tmp = File.createTempFile("import-${target.name}-", ".import", modelsDir).also { temporary = it }
            resolver.openInputStream(uri)?.use { rawIn ->
                val bis = BufferedInputStream(rawIn)
                bis.mark(4)
                val b0 = bis.read()
                val b1 = bis.read()
                bis.reset()
                val isGzip = (b0 == 0x1f && b1 == 0x8b)
                val stream: InputStream = if (isGzip) GZIPInputStream(bis) else bis
                stream.use { copyStreamToFile(it, tmp) { worker.ensureActive() } }
            } ?: throw IllegalStateException(strings.audioFileNotFound)

            val tzfVersion = readTzfMagicVersion(tmp)
                ?: throw IllegalStateException(strings.modelCorruptError)

            val vad = ensureVadInstalled()
            val err = ByteArray(256)
            val testHandle = TinyAsrNative.nativeLoadModels(tmp.absolutePath, vad.absolutePath, 1, err)
            if (testHandle == 0L) {
                val msg = err.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
                throw IllegalStateException(msg.ifBlank { strings.modelCorruptError })
            }
            TinyAsrNative.nativeFreeModels(testHandle)

            loadMutex.withLock {
                coroutineContext.ensureActive()
                publishTinyModel(targetModelId, tmp, target)
                val spec = TinyAsrCatalog.findModel(targetModelId)
                saveModelMeta(spec.id, target.length(), etag = null, versionTag = spec.versionTag, tzfVersion = tzfVersion)
            }
            Result.success(target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        } finally {
            temporary?.delete()
            activeDownloads.remove(targetModelId)
        }
    }

    // Called under loadMutex so a running session never sees freed or replaced weights.
    private fun publishTinyModel(id: String, incoming: File, target: File) {
        val existing = loadedEntries[id]
        check(existing == null || existing.refCount == 0) {
            AppLocaleManager.currentStrings().modelCannotDeleteActive
        }
        Files.move(incoming.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        if (existing != null) {
            loadedEntries.remove(id)
            TinyAsrNative.nativeFreeModels(existing.handle)
        }
    }

    suspend fun deleteModel(modelId: String): Result<Unit> = loadMutex.withLock {
        withContext(Dispatchers.IO) {
            val strings = AppLocaleManager.currentStrings()
            if (modelId in activeDownloads || (onnxUsageCounts[modelId] ?: 0) > 0) {
                return@withContext Result.failure(IllegalStateException(strings.modelCannotDeleteActive))
            }
            val loaded = loadedEntries[modelId]
            if (loaded != null && loaded.refCount > 0) {
                return@withContext Result.failure(IllegalStateException(strings.modelCannotDeleteActive))
            }
            if (loaded != null && loaded.handle != 0L) {
                TinyAsrNative.nativeFreeModels(loaded.handle)
                loadedEntries.remove(modelId)
            }
            if (modelId == TinyAsrCatalog.GIGAAM_V4) {
                if ((loadedGigaV4Online?.refCount ?: 0) > 0 || (loadedGigaV4Offline?.refCount ?: 0) > 0) {
                    return@withContext Result.failure(IllegalStateException(strings.modelCannotDeleteActive))
                }
                loadedGigaV4Online?.let {
                    if (it.handle != 0L) GigaAmV4Native.nativeFreeOnline(it.handle)
                    loadedGigaV4Online = null
                }
                loadedGigaV4Offline?.let {
                    if (it.handle != 0L) GigaAmV4Native.nativeFreeOffline(it.handle)
                    loadedGigaV4Offline = null
                }
            }
            val spec = TinyAsrCatalog.findModel(modelId)
            if (spec.isMultiFileBundle) {
                bundleDownloadDir(modelId).deleteRecursively()
                File(modelsDir, "$modelId.tmp").deleteRecursively()
            } else {
                File(downloadsDir, "${spec.fileName}.part").delete()
                File(downloadsDir, "${spec.fileName}.dlmeta").delete()
            }
            removeDownloadState(modelId)
            removeModelMeta(modelId)
            val file = getModelFile(modelId)
            val deleted = !file.exists() || file.deleteRecursively()
            if (deleted) Result.success(Unit) else Result.failure(IllegalStateException("Delete failed"))
        }
    }

    suspend fun clearDownloadCache() = withContext(Dispatchers.IO) {
        downloadsDir.listFiles()?.forEach { f ->
            val active = activeDownloads.any { id ->
                val spec = TinyAsrCatalog.findModel(id)
                f.name == spec.id || f.name.startsWith(spec.fileName)
            }
            if (!active) f.deleteRecursively()
        }
        _downloadStates.update { states -> states.filterKeys { it in activeDownloads } }
    }

    suspend fun wipeAllModelsAndDownloads() = loadMutex.withLock {
        withContext(Dispatchers.IO) {
            check(
                activeDownloads.isEmpty() &&
                    loadedEntries.values.none { it.refCount > 0 } &&
                    onnxUsageCounts.values.none { it > 0 } &&
                    (loadedGigaV4Online?.refCount ?: 0) == 0 &&
                    (loadedGigaV4Offline?.refCount ?: 0) == 0
            ) {
                AppLocaleManager.currentStrings().modelCannotDeleteActive
            }
            freeIdleModelsLocked()
            loadedEntries.clear()
            onnxUsageCounts.clear()
            _downloadStates.value = emptyMap()
            _updatesAvailable.value = emptySet()
            downloadsDir.listFiles()?.forEach { it.deleteRecursively() }
            vadInstallMutex.withLock { modelsDir.listFiles()?.forEach { it.deleteRecursively() } }
        }
    }

    private fun restorePausedDownloadStates() {
        val restored = mutableMapOf<String, ModelDownloadProgress>()
        for (spec in TinyAsrCatalog.DOWNLOAD_MODELS) {
            if (spec.isMultiFileBundle) {
                val stageDir = bundleDownloadDir(spec.id)
                val stagedBytes = recursiveLength(stageDir)
                val installed = isModelInstalled(spec.id)
                if (!installed && (stagedBytes > 0L || getBundleDir(spec.id).exists())) {
                    val total = spec.expectedTotalBytes
                    restored[spec.id] = ModelDownloadProgress(
                        modelId = spec.id,
                        phase = ModelDownloadPhase.PAUSED,
                        downloadedBytes = stagedBytes.coerceAtMost(total),
                        totalBytes = total
                    )
                }
                continue
            }
            val part = File(downloadsDir, "${spec.fileName}.part")
            val dlMeta = File(downloadsDir, "${spec.fileName}.dlmeta")
            if (part.exists() && part.length() > 0L) {
                val total = readDlMeta(dlMeta)?.totalBytes?.takeIf { it > 0L } ?: (spec.sizeMb * 1_048_576f).toLong()
                restored[spec.id] = ModelDownloadProgress(
                    modelId = spec.id,
                    phase = ModelDownloadPhase.PAUSED,
                    downloadedBytes = part.length(),
                    totalBytes = total
                )
            }
        }
        if (restored.isNotEmpty()) {
            _downloadStates.value = restored
        }
    }

    private fun updateDownloadState(progress: ModelDownloadProgress) {
        _downloadStates.update { it + (progress.modelId to progress) }
    }

    private fun removeDownloadState(modelId: String) {
        _downloadStates.update { it - modelId }
    }

    private fun matchesSha256(file: File, expected: String, checkCancelled: () -> Unit): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == expected
    }

    private fun saveDlMeta(file: File, totalBytes: Long, etag: String?) {
        runCatching {
            val entry = DlMetaEntry(totalBytes = totalBytes, etag = etag?.takeIf { it.isNotBlank() })
            file.writeText(metaJson.encodeToString(DlMetaEntry.serializer(), entry))
        }
    }

    private fun readDlMeta(file: File): DlMetaEntry? {
        if (!file.isFile) return null
        return runCatching { metaJson.decodeFromString(DlMetaEntry.serializer(), file.readText()) }.getOrNull()
    }

    private fun savePausedDownload(modelId: String, downloadedBytes: Long, totalBytes: Long) {
        updateDownloadState(
            ModelDownloadProgress(modelId, ModelDownloadPhase.PAUSED, downloadedBytes, totalBytes)
        )
    }

    private fun readMetaJson(): Map<String, InstalledMetaEntry> = synchronized(metaLock) {
        if (!metaFile.exists()) return emptyMap()
        return runCatching {
            metaJson.decodeFromString(metaMapSerializer, metaFile.readText())
        }.getOrElse { emptyMap() }
    }

    private fun saveModelMeta(modelId: String, sizeBytes: Long, etag: String?, versionTag: String, tzfVersion: String?) {
        synchronized(metaLock) {
            val root = if (metaFile.exists()) {
                runCatching { metaJson.decodeFromString(metaMapSerializer, metaFile.readText()) }.getOrElse { emptyMap() }
            } else {
                emptyMap()
            }
            val updated = root + (
                modelId to InstalledMetaEntry(
                    sizeBytes = sizeBytes,
                    versionTag = versionTag,
                    installedAt = System.currentTimeMillis(),
                    etag = etag?.takeIf { it.isNotBlank() },
                    tzfVersion = tzfVersion?.takeIf { it.isNotBlank() }
                )
            )
            metaFile.writeText(metaJson.encodeToString(metaMapSerializer, updated))
        }
    }

    private fun removeModelMeta(modelId: String) {
        synchronized(metaLock) {
            if (!metaFile.exists()) return
            val root = runCatching { metaJson.decodeFromString(metaMapSerializer, metaFile.readText()) }.getOrNull() ?: return
            metaFile.writeText(metaJson.encodeToString(metaMapSerializer, root - modelId))
        }
    }

    private fun unpackAssetGz(assetPath: String, destFile: File) {
        val tmp = File(destFile.parentFile, "${destFile.name}.tmp")
        try {
            assetOpener(assetPath).use { assetIn ->
                GZIPInputStream(BufferedInputStream(assetIn, 64 * 1024)).use { gzIn ->
                    copyStreamToFile(gzIn, tmp)
                }
            }
            if (destFile.exists()) destFile.delete()
            if (!tmp.renameTo(destFile)) {
                throw IllegalStateException("Failed to unpack $assetPath")
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    private fun copyStreamToFile(input: InputStream, dest: File, checkActive: () -> Unit = {}) {
        FileOutputStream(dest).use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkActive()
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            out.fd.sync()
        }
    }

    private class PausedByUserException : CancellationException("Paused")

    companion object {
        /** Checks for TinyASR TZF header ("TZF1" .. "TZF6") and minimum payload size. */
        fun readTzfMagicVersion(file: File): String? {
            if (!file.exists() || file.length() < 1024L) return null
            val hdr = ByteArray(4)
            FileInputStream(file).use { fis ->
                if (fis.read(hdr) != 4) return null
            }
            return parseTzfMagic(hdr)
        }

        fun parseTzfMagic(header: ByteArray): String? {
            if (header.size < 4) return null
            val b0 = header[0].toInt().toChar()
            val b1 = header[1].toInt().toChar()
            val b2 = header[2].toInt().toChar()
            val b3 = header[3].toInt().toChar()
            if (b0 == 'T' && b1 == 'Z' && b2 == 'F' && b3 in '1'..'6') {
                return "TZF$b3"
            }
            return null
        }
    }
}
