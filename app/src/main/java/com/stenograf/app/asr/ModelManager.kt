package com.stenograf.app.asr

import android.content.Context
import android.net.Uri
import com.stenograf.app.i18n.AppLocaleManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

sealed interface ModelLoadState {
    data object Idle : ModelLoadState
    data class Preparing(val modelId: String, val message: String) : ModelLoadState
    data class Ready(val modelId: String, val langs: List<String>, val chunkMs: Int) : ModelLoadState
    data class Error(val modelId: String, val message: String) : ModelLoadState
}

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
    val installedFormat: String? = null
) {
    val isDownloading: Boolean
        get() = downloadProgress?.phase == ModelDownloadPhase.DOWNLOADING ||
            downloadProgress?.phase == ModelDownloadPhase.VERIFYING
    val isPaused: Boolean
        get() = downloadProgress?.phase == ModelDownloadPhase.PAUSED
}

data class ModelLease(
    val modelId: String,
    val handle: Long,
    val langs: List<String>,
    val chunkMs: Int
)

private data class LoadedModelEntry(
    val modelId: String,
    val handle: Long,
    val threads: Int,
    val langs: List<String>,
    val chunkMs: Int,
    var refCount: Int = 0
)

class ModelManager(private val context: Context) {
    private val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }
    private val downloadsDir: File = File(context.cacheDir, "model_downloads").apply { mkdirs() }
    private val metaFile: File = File(modelsDir, "model_meta.json")
    private val loadMutex = Mutex()
    private val metaLock = Any()

    private val loadedEntries = ConcurrentHashMap<String, LoadedModelEntry>()

    // 0 = none, 1 = pause requested, 2 = cancel requested
    private val controlFlags = ConcurrentHashMap<String, AtomicInteger>()

    private val _loadState = MutableStateFlow<ModelLoadState>(ModelLoadState.Idle)
    val loadState: StateFlow<ModelLoadState> = _loadState.asStateFlow()

    private val _downloadStates = MutableStateFlow<Map<String, ModelDownloadProgress>>(emptyMap())
    val downloadStates: StateFlow<Map<String, ModelDownloadProgress>> = _downloadStates.asStateFlow()

    private val _updatesAvailable = MutableStateFlow<Set<String>>(emptySet())
    val updatesAvailable: StateFlow<Set<String>> = _updatesAvailable.asStateFlow()

    init {
        restorePausedDownloadStates()
    }

    fun getVadFile(): File = File(modelsDir, TinyAsrCatalog.VAD_UNPACKED_NAME)

    fun getModelFile(modelId: String): File {
        val spec = TinyAsrCatalog.findModel(modelId)
        return File(modelsDir, spec.unpackedName)
    }

    fun isModelInstalled(modelId: String): Boolean {
        val file = getModelFile(modelId)
        return file.exists() && file.length() > 1_000_000L
    }

    fun getModelsTotalBytes(): Long {
        return modelsDir.listFiles()
            ?.filter { !it.name.endsWith(".tmp") && !it.name.endsWith(".import") }
            ?.sumOf { it.length() } ?: 0L
    }

    fun getDownloadsCacheBytes(): Long {
        return downloadsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    fun getAllModelStatuses(): List<ModelInstallStatus> {
        val dls = _downloadStates.value
        val updates = _updatesAvailable.value
        val meta = readMetaJson()
        return TinyAsrCatalog.MODELS.map { spec ->
            val f = getModelFile(spec.id)
            val installed = f.exists() && f.length() > 1_000_000L
            val mObj = meta.optJSONObject(spec.id)
            val fmt = mObj?.optString("tzfVersion", "")?.takeIf { it.isNotBlank() }
            ModelInstallStatus(
                spec = spec,
                isInstalled = installed,
                sizeBytes = if (installed) f.length() else 0L,
                downloadProgress = dls[spec.id],
                updateAvailable = updates.contains(spec.id),
                installedFormat = fmt
            )
        }
    }

    fun cleanupOrphanedDownloads() {
        modelsDir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".tmp") || f.name.endsWith(".import") || f.name.startsWith("ggml-")) {
                f.delete()
            }
        }
        val activeIds = _downloadStates.value
            .filterValues { it.phase == ModelDownloadPhase.DOWNLOADING || it.phase == ModelDownloadPhase.PAUSED || it.phase == ModelDownloadPhase.VERIFYING }
            .keys
        downloadsDir.listFiles()?.forEach { f ->
            val belongsToActive = activeIds.any { f.name.startsWith(TinyAsrCatalog.findModel(it).fileName) }
            if (!belongsToActive || f.name.startsWith("ggml-")) f.delete()
        }
    }

    suspend fun ensureVadInstalled(): File = withContext(Dispatchers.IO) {
        val vadFile = getVadFile()
        if (vadFile.exists() && vadFile.length() > 100_000L) return@withContext vadFile
        unpackAssetGz("models/${TinyAsrCatalog.VAD_ASSET_NAME}", vadFile)
        vadFile
    }

    suspend fun ensureModelInstalled(modelId: String): File = withContext(Dispatchers.IO) {
        val spec = TinyAsrCatalog.findModel(modelId)
        val target = getModelFile(modelId)
        if (target.exists() && target.length() > 1_000_000L) return@withContext target
        val strings = AppLocaleManager.currentStrings()
        if (spec.bundledInApk) {
            _loadState.value = ModelLoadState.Preparing(modelId, "${strings.preparingModel} (${spec.id.uppercase()})")
            unpackAssetGz("models/${spec.assetName}", target)
            saveModelMeta(spec.id, target.length(), etag = null, versionTag = spec.versionTag, tzfVersion = readTzfMagicVersion(target))
            return@withContext target
        }
        throw IllegalStateException("${strings.modelRequiredInstallPrompt}: ${spec.localizedTitle(strings)}")
    }

    suspend fun acquireModelLease(modelId: String, requestedThreads: Int): ModelLease = loadMutex.withLock {
        withContext(Dispatchers.IO) {
            val existing = loadedEntries[modelId]
            if (existing != null && existing.handle != 0L) {
                if (existing.threads == requestedThreads || existing.refCount > 0) {
                    existing.refCount += 1
                    _loadState.value = ModelLoadState.Ready(modelId, existing.langs, existing.chunkMs)
                    return@withContext ModelLease(modelId, existing.handle, existing.langs, existing.chunkMs)
                } else {
                    TinyAsrNative.nativeFreeModels(existing.handle)
                    loadedEntries.remove(modelId)
                }
            }

            // Free any idle (unleased) models from other IDs to keep RAM tight
            val idleKeys = loadedEntries.entries.filter { it.value.refCount <= 0 && it.key != modelId }.map { it.key }
            for (k in idleKeys) {
                loadedEntries.remove(k)?.let { entry ->
                    if (entry.handle != 0L) TinyAsrNative.nativeFreeModels(entry.handle)
                }
            }

            val vadFile = ensureVadInstalled()
            val asrFile = ensureModelInstalled(modelId)
            val strings = AppLocaleManager.currentStrings()
            _loadState.value = ModelLoadState.Preparing(modelId, "${strings.loadingModelProgress} (${modelId.uppercase()})")

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
                _loadState.value = ModelLoadState.Error(modelId, errText)
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
            _loadState.value = ModelLoadState.Ready(modelId, langs, chunkMs)
            ModelLease(modelId, handle, langs, chunkMs)
        }
    }

    suspend fun releaseModelLease(lease: ModelLease?) = loadMutex.withLock {
        if (lease == null) return@withLock
        val entry = loadedEntries[lease.modelId] ?: return@withLock
        entry.refCount = (entry.refCount - 1).coerceAtLeast(0)
    }

    suspend fun downloadModel(modelId: String): Result<File> = withContext(Dispatchers.IO) {
        val strings = AppLocaleManager.currentStrings()
        val spec = TinyAsrCatalog.findModel(modelId)
        val target = getModelFile(modelId)
        val partFile = File(downloadsDir, "${spec.fileName}.part")
        val dlMetaFile = File(downloadsDir, "${spec.fileName}.dlmeta")
        val tmpUnpacked = File(modelsDir, "${spec.unpackedName}.tmp")

        val flag = controlFlags.computeIfAbsent(modelId) { AtomicInteger(0) }
        flag.set(0)

        var conn: HttpURLConnection? = null
        try {
            val expectedFallbackBytes = (spec.sizeMb * 1_048_576f).toLong()
            var existingBytes = if (partFile.exists()) partFile.length() else 0L

            updateDownloadState(
                ModelDownloadProgress(
                    modelId = modelId,
                    phase = ModelDownloadPhase.DOWNLOADING,
                    downloadedBytes = existingBytes,
                    totalBytes = expectedFallbackBytes
                )
            )

            conn = (URL(spec.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                if (existingBytes > 0L) {
                    setRequestProperty("Range", "bytes=$existingBytes-")
                }
            }

            val code = conn.responseCode
            val resumeAccepted = code == HttpURLConnection.HTTP_PARTIAL
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
            val totalBytes = when {
                resumeAccepted && contentLength > 0L -> existingBytes + contentLength
                !resumeAccepted && contentLength > 0L -> contentLength
                else -> expectedFallbackBytes
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
                            when (flag.get()) {
                                1 -> { // Pause
                                    updateDownloadState(
                                        ModelDownloadProgress(
                                            modelId = modelId,
                                            phase = ModelDownloadPhase.PAUSED,
                                            downloadedBytes = downloaded,
                                            totalBytes = totalBytes
                                        )
                                    )
                                    throw PausedByUserException()
                                }
                                2 -> { // Cancel
                                    throw CancellationException("Cancelled")
                                }
                            }

                            val read = input.read(buf)
                            if (read <= 0) break
                            raf.write(buf, 0, read)
                            downloaded += read
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

            if (flag.get() == 1) {
                updateDownloadState(
                    ModelDownloadProgress(modelId, ModelDownloadPhase.PAUSED, partFile.length(), totalBytes)
                )
                throw PausedByUserException()
            } else if (flag.get() == 2) {
                throw CancellationException("Cancelled")
            }

            // Verification phase: GZIP decompress + TZF magic + native tinyasr_load check
            updateDownloadState(
                ModelDownloadProgress(
                    modelId = modelId,
                    phase = ModelDownloadPhase.VERIFYING,
                    downloadedBytes = totalBytes,
                    totalBytes = totalBytes
                )
            )

            try {
                GZIPInputStream(BufferedInputStream(FileInputStream(partFile), 64 * 1024)).use { gz ->
                    copyStreamToFile(gz, tmpUnpacked)
                }
            } catch (t: Throwable) {
                // Corrupted compressed archive; remove .part so retry starts clean
                partFile.delete()
                dlMetaFile.delete()
                throw IllegalStateException(strings.modelCorruptError, t)
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

            if (target.exists()) target.delete()
            if (!tmpUnpacked.renameTo(target)) {
                throw IllegalStateException(strings.modelDownloadError)
            }
            partFile.delete()
            dlMetaFile.delete()

            // Free any previously loaded handle for this modelId if idle so next run uses updated model
            loadedEntries[modelId]?.let { entry ->
                if (entry.refCount == 0) {
                    TinyAsrNative.nativeFreeModels(entry.handle)
                    loadedEntries.remove(modelId)
                }
            }

            saveModelMeta(spec.id, target.length(), etag, spec.versionTag, tzfVersion)
            _updatesAvailable.value = _updatesAvailable.value - spec.id
            removeDownloadState(modelId)
            Result.success(target)
        } catch (_: PausedByUserException) {
            tmpUnpacked.delete()
            Result.failure(CancellationException("Paused"))
        } catch (ce: CancellationException) {
            tmpUnpacked.delete()
            if (flag.get() == 2) {
                partFile.delete()
                dlMetaFile.delete()
                removeDownloadState(modelId)
            }
            Result.failure(ce)
        } catch (t: Throwable) {
            tmpUnpacked.delete()
            // Keep .part on transient network errors so user can resume unless corrupt
            val msg = t.message?.takeIf { it.isNotBlank() } ?: strings.modelDownloadError
            val currentBytes = if (partFile.exists()) partFile.length() else 0L
            updateDownloadState(
                ModelDownloadProgress(
                    modelId = modelId,
                    phase = if (currentBytes > 0L) ModelDownloadPhase.PAUSED else ModelDownloadPhase.ERROR,
                    downloadedBytes = currentBytes,
                    totalBytes = (spec.sizeMb * 1_048_576f).toLong(),
                    errorMessage = msg
                )
            )
            Result.failure(t)
        } finally {
            conn?.disconnect()
        }
    }

    fun pauseDownload(modelId: String) {
        controlFlags.computeIfAbsent(modelId) { AtomicInteger(0) }.set(1)
    }

    fun cancelDownload(modelId: String) {
        controlFlags.computeIfAbsent(modelId) { AtomicInteger(0) }.set(2)
        val spec = TinyAsrCatalog.findModel(modelId)
        File(downloadsDir, "${spec.fileName}.part").delete()
        File(downloadsDir, "${spec.fileName}.dlmeta").delete()
        removeDownloadState(modelId)
    }

    suspend fun checkForModelUpdates(): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, Boolean>()
        val meta = readMetaJson()
        val updateSet = mutableSetOf<String>()
        for (spec in TinyAsrCatalog.MODELS) {
            if (!isModelInstalled(spec.id)) {
                results[spec.id] = false
                continue
            }
            val savedEtag = meta.optJSONObject(spec.id)?.optString("etag", "").orEmpty()
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
        val target = getModelFile(targetModelId)
        val tmp = File(modelsDir, "${target.name}.import")
        try {
            context.contentResolver.openInputStream(uri)?.use { rawIn ->
                val bis = BufferedInputStream(rawIn)
                bis.mark(4)
                val b0 = bis.read()
                val b1 = bis.read()
                bis.reset()
                val isGzip = (b0 == 0x1f && b1 == 0x8b)
                val stream: InputStream = if (isGzip) GZIPInputStream(bis) else bis
                stream.use { copyStreamToFile(it, tmp) }
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

            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                throw IllegalStateException(strings.modelDownloadError)
            }
            val spec = TinyAsrCatalog.findModel(targetModelId)
            saveModelMeta(spec.id, target.length(), etag = null, versionTag = spec.versionTag, tzfVersion = tzfVersion)
            Result.success(target)
        } catch (t: Throwable) {
            tmp.delete()
            Result.failure(t)
        }
    }

    suspend fun deleteModel(modelId: String): Result<Unit> = loadMutex.withLock {
        withContext(Dispatchers.IO) {
            val strings = AppLocaleManager.currentStrings()
            val loaded = loadedEntries[modelId]
            if (loaded != null && loaded.refCount > 0) {
                return@withContext Result.failure(IllegalStateException(strings.modelCannotDeleteActive))
            }
            if (loaded != null && loaded.handle != 0L) {
                TinyAsrNative.nativeFreeModels(loaded.handle)
                loadedEntries.remove(modelId)
                if (_loadState.value is ModelLoadState.Ready &&
                    (_loadState.value as ModelLoadState.Ready).modelId == modelId
                ) {
                    _loadState.value = ModelLoadState.Idle
                }
            }
            val spec = TinyAsrCatalog.findModel(modelId)
            File(downloadsDir, "${spec.fileName}.part").delete()
            File(downloadsDir, "${spec.fileName}.dlmeta").delete()
            removeDownloadState(modelId)
            removeModelMeta(modelId)
            val file = getModelFile(modelId)
            val deleted = !file.exists() || file.delete()
            if (deleted) Result.success(Unit) else Result.failure(IllegalStateException("Delete failed"))
        }
    }

    suspend fun clearDownloadCache() = withContext(Dispatchers.IO) {
        downloadsDir.listFiles()?.forEach { f ->
            val isActivelyDownloading = _downloadStates.value.values.any {
                it.phase == ModelDownloadPhase.DOWNLOADING &&
                    f.name.startsWith(TinyAsrCatalog.findModel(it.modelId).fileName)
            }
            if (!isActivelyDownloading) {
                f.delete()
            }
        }
        val activeOnly = _downloadStates.value.filterValues { it.phase == ModelDownloadPhase.DOWNLOADING }
        _downloadStates.value = activeOnly
    }

    suspend fun wipeAllModelsAndDownloads() = loadMutex.withLock {
        withContext(Dispatchers.IO) {
            controlFlags.values.forEach { it.set(2) }
            loadedEntries.values.forEach { entry ->
                if (entry.handle != 0L) TinyAsrNative.nativeFreeModels(entry.handle)
            }
            loadedEntries.clear()
            _loadState.value = ModelLoadState.Idle
            _downloadStates.value = emptyMap()
            _updatesAvailable.value = emptySet()
            downloadsDir.listFiles()?.forEach { it.delete() }
            modelsDir.listFiles()?.forEach { it.delete() }
        }
    }

    suspend fun releaseLoadedModels() = loadMutex.withLock {
        val idleOnly = loadedEntries.entries.filter { it.value.refCount <= 0 }.map { it.key }
        for (k in idleOnly) {
            loadedEntries.remove(k)?.let { entry ->
                if (entry.handle != 0L) TinyAsrNative.nativeFreeModels(entry.handle)
            }
        }
        if (loadedEntries.isEmpty()) {
            _loadState.value = ModelLoadState.Idle
        }
    }

    private fun restorePausedDownloadStates() {
        val restored = mutableMapOf<String, ModelDownloadProgress>()
        for (spec in TinyAsrCatalog.MODELS) {
            val part = File(downloadsDir, "${spec.fileName}.part")
            val dlMeta = File(downloadsDir, "${spec.fileName}.dlmeta")
            if (part.exists() && part.length() > 0L) {
                val total = readDlMetaTotal(dlMeta) ?: (spec.sizeMb * 1_048_576f).toLong()
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
        _downloadStates.value = _downloadStates.value + (progress.modelId to progress)
    }

    private fun removeDownloadState(modelId: String) {
        _downloadStates.value = _downloadStates.value - modelId
    }

    private fun saveDlMeta(file: File, totalBytes: Long, etag: String?) {
        runCatching {
            val obj = JSONObject().apply {
                put("totalBytes", totalBytes)
                if (!etag.isNullOrBlank()) put("etag", etag)
            }
            file.writeText(obj.toString())
        }
    }

    private fun readDlMetaTotal(file: File): Long? {
        if (!file.exists()) return null
        return runCatching {
            JSONObject(file.readText()).optLong("totalBytes", 0L).takeIf { it > 0L }
        }.getOrNull()
    }

    private fun readMetaJson(): JSONObject = synchronized(metaLock) {
        if (!metaFile.exists()) return JSONObject()
        return runCatching { JSONObject(metaFile.readText()) }.getOrElse { JSONObject() }
    }

    private fun saveModelMeta(modelId: String, sizeBytes: Long, etag: String?, versionTag: String, tzfVersion: String?) {
        synchronized(metaLock) {
            val root = if (metaFile.exists()) {
                runCatching { JSONObject(metaFile.readText()) }.getOrElse { JSONObject() }
            } else JSONObject()
            val entry = JSONObject().apply {
                put("sizeBytes", sizeBytes)
                put("versionTag", versionTag)
                put("installedAt", System.currentTimeMillis())
                if (!etag.isNullOrBlank()) put("etag", etag)
                if (!tzfVersion.isNullOrBlank()) put("tzfVersion", tzfVersion)
            }
            root.put(modelId, entry)
            metaFile.writeText(root.toString())
        }
    }

    private fun removeModelMeta(modelId: String) {
        synchronized(metaLock) {
            if (!metaFile.exists()) return
            val root = runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: return
            root.remove(modelId)
            metaFile.writeText(root.toString())
        }
    }

    private fun unpackAssetGz(assetPath: String, destFile: File) {
        val tmp = File(destFile.parentFile, "${destFile.name}.tmp")
        try {
            context.assets.open(assetPath).use { assetIn ->
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

    private fun copyStreamToFile(input: InputStream, dest: File) {
        FileOutputStream(dest).use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
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
