package com.stenograf.app.export

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.stenograf.app.asr.AsrJsonParser
import com.stenograf.app.asr.AsrTextFormatter
import com.stenograf.app.asr.TinyAsrCatalog
import com.stenograf.app.asr.TranscriptWord
import com.stenograf.app.data.model.ParagraphEntity
import com.stenograf.app.data.model.RecordingEntity
import com.stenograf.app.i18n.AppLocaleManager
import com.stenograf.app.i18n.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

enum class ExportFormat(val ext: String, val mimeType: String) {
    TXT("txt", "text/plain"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    PDF("pdf", "application/pdf"),
    SRT("srt", "application/x-subrip");

    fun label(strings: AppStrings): String = when (this) {
        TXT -> strings.formatTxt
        DOCX -> strings.formatDocx
        PDF -> strings.formatPdf
        SRT -> strings.formatSrt
    }
}

object TranscriptExporter {

    private val unsafeFilenameCharacters = Regex("""[\\/:*?"<>|\p{Cntrl}]""")

    fun safeBaseName(title: String): String {
        val clean = title.replace(unsafeFilenameCharacters, "_").trim().take(64)
        return clean.trimEnd(' ', '.').dropLastWhile { it.isHighSurrogate() }.ifBlank { "recording" }
    }

    fun mediaMimeType(file: File): String {
        val extension = file.extension.lowercase(Locale.ROOT)
        return when (extension) {
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "opus" -> "audio/ogg"
            "mkv" -> "video/x-matroska"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        }
    }

    suspend fun exportToFile(
        context: Context,
        recording: RecordingEntity,
        paragraphs: List<ParagraphEntity>,
        format: ExportFormat,
        includeTimestamps: Boolean = true
    ): File = withContext(Dispatchers.IO) {
        val root = File(context.cacheDir, "exports").apply { mkdirs() }
        val directory = Files.createTempDirectory(root.toPath(), "share_").toFile()
        val file = File(directory, "${safeBaseName(recording.title)}.${format.ext}")
        try {
            writeExport(file, recording, paragraphs, format, includeTimestamps)
            coroutineContext.ensureActive()
            file
        } catch (failure: Exception) {
            directory.deleteRecursively()
            throw failure
        }
    }

    suspend fun exportToUri(
        context: Context,
        destUri: Uri,
        recording: RecordingEntity,
        paragraphs: List<ParagraphEntity>,
        format: ExportFormat,
        includeTimestamps: Boolean = true
    ) = withContext(Dispatchers.IO) {
        val tempFile = File.createTempFile("export_", ".${format.ext}", context.cacheDir)
        try {
            writeExport(tempFile, recording, paragraphs, format, includeTimestamps)
            copyToUri(context, tempFile, destUri)
        } finally {
            tempFile.delete()
        }
    }

    suspend fun copyAudioFileToUri(
        context: Context,
        audioFile: File,
        destUri: Uri
    ) = withContext(Dispatchers.IO) {
        check(audioFile.isFile && audioFile.length() > 0L) { AppLocaleManager.currentStrings().audioFileNotFound }
        copyToUri(context, audioFile, destUri)
    }

    private suspend fun copyToUri(context: Context, source: File, destination: Uri) {
        val worker = coroutineContext
        worker.ensureActive()
        source.inputStream().use { input ->
            context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                copyWhileActive(input, output) { worker.ensureActive() }
            } ?: error(AppLocaleManager.currentStrings().exportError)
        }
    }

    private fun copyWhileActive(input: InputStream, output: OutputStream, checkActive: () -> Unit) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count == -1) return
            output.write(buffer, 0, count)
        }
    }

    private fun writeExport(
        file: File,
        recording: RecordingEntity,
        paragraphs: List<ParagraphEntity>,
        format: ExportFormat,
        includeTimestamps: Boolean
    ) {
        val strings = AppLocaleManager.currentStrings()
        when (format) {
            ExportFormat.TXT -> file.writeText(buildTxt(recording, paragraphs, includeTimestamps, strings))
            ExportFormat.SRT -> file.writeText(buildSrt(paragraphs))
            ExportFormat.PDF -> writePdf(file, recording, paragraphs, includeTimestamps, strings)
            ExportFormat.DOCX -> writeDocx(file, recording, paragraphs, includeTimestamps, strings)
        }
    }

    fun buildPlainText(paragraphs: List<ParagraphEntity>, includeTimestamps: Boolean): String {
        return paragraphs.joinToString("\n\n") { p ->
            val prefix = if (includeTimestamps) "[${AsrTextFormatter.formatTimestampSec(p.startSec)}] " else ""
            val speakerPrefix = if (!p.speaker.isNullOrBlank()) "${p.speaker}: " else ""
            val tag = if (p.isBookmark && !p.bookmarkLabel.isNullOrBlank()) "⚑ ${p.bookmarkLabel}\n" else ""
            "$tag$prefix$speakerPrefix${p.text}"
        }
    }

    private fun buildTxt(
        recording: RecordingEntity,
        paragraphs: List<ParagraphEntity>,
        includeTimestamps: Boolean,
        strings: AppStrings
    ): String {
        val dateStr = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(recording.createdAtMs))
        val durStr = AsrTextFormatter.formatDurationMs(recording.durationMs)
        val langStr = TinyAsrCatalog.languageNativeName(recording.detectedLanguage)
        val header = buildString {
            appendLine(recording.title)
            appendLine("${strings.docMetaDate}: $dateStr · ${strings.docMetaDuration}: $durStr · ${strings.docMetaLanguage}: $langStr")
            appendLine("─".repeat(48))
            appendLine()
        }
        return header + buildPlainText(paragraphs, includeTimestamps)
    }

    private fun buildSrt(paragraphs: List<ParagraphEntity>): String {
        val cues = mutableListOf<Triple<Float, Float, String>>()
        for (p in paragraphs) {
            val words = AsrJsonParser.parseWords(p.wordsJson)
            if (words.isEmpty()) {
                if (p.text.isNotBlank()) {
                    cues.add(Triple(p.startSec, p.endSec.coerceAtLeast(p.startSec + 1.0f), p.text))
                }
                continue
            }
            val chunk = mutableListOf<TranscriptWord>()
            for (w in words) {
                chunk.add(w)
                val joined = AsrTextFormatter.joinWords(chunk)
                val dur = w.end - chunk.first().start
                if (AsrTextFormatter.isSentenceEnd(w.text) || joined.length >= 68 || chunk.size >= 11 || dur >= 5.5f) {
                    cues.add(Triple(chunk.first().start, chunk.last().end.coerceAtLeast(chunk.first().start + 0.4f), joined))
                    chunk.clear()
                }
            }
            if (chunk.isNotEmpty()) {
                cues.add(
                    Triple(
                        chunk.first().start,
                        chunk.last().end.coerceAtLeast(chunk.first().start + 0.4f),
                        AsrTextFormatter.joinWords(chunk)
                    )
                )
            }
        }
        return buildString {
            cues.forEachIndexed { idx, (start, end, text) ->
                appendLine(idx + 1)
                appendLine("${AsrTextFormatter.formatSrtTime(start)} --> ${AsrTextFormatter.formatSrtTime(end)}")
                appendLine(text)
                appendLine()
            }
        }
    }

    private fun writePdf(
        file: File,
        recording: RecordingEntity,
        paragraphs: List<ParagraphEntity>,
        includeTimestamps: Boolean,
        strings: AppStrings
    ) {
        val pageWidth = 595
        val pageHeight = 842
        val margin = 46
        val contentWidth = pageWidth - margin * 2
        val bottom = pageHeight - margin

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 17f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            color = Color.parseColor("#111827")
        }
        val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10f
            color = Color.parseColor("#6B7280")
        }
        val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            color = Color.parseColor("#2563EB")
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11.5f
            color = Color.parseColor("#1F2937")
        }

        val pdf = PdfDocument()
        try {
            var pageNumber = 0
            var currentPage: PdfDocument.Page? = null
            var y = margin

            fun finishPage() {
                val page = currentPage ?: return
                currentPage = null
                try {
                    val pageLabel = "${strings.docPageShort} $pageNumber"
                    val x = pageWidth - margin - metaPaint.measureText(pageLabel)
                    page.canvas.drawText(pageLabel, x, (pageHeight - 24).toFloat(), metaPaint)
                } finally {
                    pdf.finishPage(page)
                }
            }

            fun startPage() {
                finishPage()
                pageNumber++
                currentPage = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                y = margin
            }

            fun drawText(text: String, paint: TextPaint, spacingAfter: Int) {
                if (text.isBlank()) return
                val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                    .setLineSpacing(2f, 1f)
                    .build()
                var firstLine = 0
                while (firstLine < layout.lineCount) {
                    val top = layout.getLineTop(firstLine)
                    var lastLine = firstLine
                    while (lastLine < layout.lineCount && y + layout.getLineBottom(lastLine) - top <= bottom) {
                        lastLine++
                    }
                    if (lastLine == firstLine) {
                        startPage()
                        continue
                    }
                    val height = layout.getLineBottom(lastLine - 1) - top
                    val canvas = checkNotNull(currentPage).canvas
                    val saved = canvas.save()
                    try {
                        canvas.translate(margin.toFloat(), (y - top).toFloat())
                        canvas.clipRect(0f, top.toFloat(), contentWidth.toFloat(), (top + height).toFloat())
                        layout.draw(canvas)
                    } finally {
                        canvas.restoreToCount(saved)
                    }
                    y += height
                    firstLine = lastLine
                    if (firstLine < layout.lineCount) startPage()
                }
                y += spacingAfter
            }

            startPage()
            try {
                drawText(recording.title, titlePaint, 8)
                val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(recording.createdAtMs))
                val duration = AsrTextFormatter.formatDurationMs(recording.durationMs)
                val language = TinyAsrCatalog.languageNativeName(recording.detectedLanguage)
                drawText("$date · ${strings.docMetaDuration}: $duration · ${strings.docMetaLanguage}: $language", metaPaint, 16)

                for (paragraph in paragraphs) {
                    if (y > bottom - 44) startPage()
                    if (paragraph.isBookmark && !paragraph.bookmarkLabel.isNullOrBlank()) {
                        drawText("⚑ ${paragraph.bookmarkLabel}", labelPaint, 3)
                    }
                    val timestamp = if (includeTimestamps) AsrTextFormatter.formatTimestampSec(paragraph.startSec) else null
                    val label = listOfNotNull(timestamp, paragraph.speaker?.takeIf { it.isNotBlank() }).joinToString(" · ")
                    drawText(label, labelPaint, 3)
                    drawText(paragraph.text, bodyPaint, 9)
                }
            } finally {
                finishPage()
            }
            file.outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    private fun writeDocx(
        file: File,
        recording: RecordingEntity,
        paragraphs: List<ParagraphEntity>,
        includeTimestamps: Boolean,
        strings: AppStrings
    ) {
        val dateStr = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(recording.createdAtMs))
        val durStr = AsrTextFormatter.formatDurationMs(recording.durationMs)
        val langStr = TinyAsrCatalog.languageNativeName(recording.detectedLanguage)

        val bodyXml = buildString {
            append("""<w:p><w:pPr><w:spacing w:after="120"/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val="32"/></w:rPr><w:t>${xmlText(recording.title)}</w:t></w:r></w:p>""")
            append("""<w:p><w:pPr><w:spacing w:after="240"/></w:pPr><w:r><w:rPr><w:color w:val="6B7280"/><w:sz w:val="20"/></w:rPr><w:t>${xmlText("$dateStr · ${strings.docMetaDuration}: $durStr · ${strings.docMetaLanguage}: $langStr")}</w:t></w:r></w:p>""")

            for (p in paragraphs) {
                if (p.isBookmark && !p.bookmarkLabel.isNullOrBlank()) {
                    append("""<w:p><w:pPr><w:spacing w:before="120" w:after="40"/></w:pPr><w:r><w:rPr><w:b/><w:color w:val="2563EB"/><w:sz w:val="20"/></w:rPr><w:t>${xmlText("⚑ ${p.bookmarkLabel}")}</w:t></w:r></w:p>""")
                }
                append("""<w:p><w:pPr><w:spacing w:after="160" w:line="300" w:lineRule="auto"/></w:pPr>""")
                if (includeTimestamps) {
                    append("""<w:r><w:rPr><w:b/><w:color w:val="2563EB"/><w:sz w:val="20"/></w:rPr><w:t xml:space="preserve">[${xmlText(AsrTextFormatter.formatTimestampSec(p.startSec))}] </w:t></w:r>""")
                }
                if (!p.speaker.isNullOrBlank()) {
                    append("""<w:r><w:rPr><w:b/><w:sz w:val="22"/></w:rPr><w:t xml:space="preserve">${xmlText(p.speaker)}: </w:t></w:r>""")
                }
                append("""<w:r><w:rPr><w:sz w:val="23"/></w:rPr><w:t xml:space="preserve">${docxText(p.text)}</w:t></w:r></w:p>""")
            }
        }

        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

        val documentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
    $bodyXml
    <w:sectPr>
      <w:pgSz w:w="11906" w:h="16838"/>
      <w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134"/>
    </w:sectPr>
  </w:body>
</w:document>"""

        ZipOutputStream(FileOutputStream(file)).use { zip ->
            fun addEntry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            addEntry("[Content_Types].xml", contentTypes)
            addEntry("_rels/.rels", rels)
            addEntry("word/document.xml", documentXml)
        }
    }

    internal fun xmlText(text: String): String = buildString {
        val points = text.codePoints().iterator()
        while (points.hasNext()) {
            when (val point = points.nextInt()) {
                '&'.code -> append("&amp;")
                '<'.code -> append("&lt;")
                '>'.code -> append("&gt;")
                '"'.code -> append("&quot;")
                '\''.code -> append("&apos;")
                0x09, 0x0A, 0x0D, in 0x20..0xD7FF, in 0xE000..0xFFFD, in 0x10000..0x10FFFF ->
                    appendCodePoint(point)
            }
        }
    }

    internal fun docxText(text: String): String =
        xmlText(text.replace("\r\n", "\n").replace('\r', '\n'))
            .replace("\n", """</w:t><w:br/><w:t xml:space="preserve">""")
            .replace("\t", """</w:t><w:tab/><w:t xml:space="preserve">""")

    fun shareFile(context: Context, file: File, mimeType: String, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    fun shareText(context: Context, title: String, text: String) {
        val strings = AppLocaleManager.currentStrings()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(intent, strings.shareText).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
