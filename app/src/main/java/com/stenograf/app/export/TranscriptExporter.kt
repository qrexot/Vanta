package com.stenograf.app.export

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
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
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

    fun safeBaseName(title: String): String {
        val clean = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return clean.ifBlank { "recording" }.take(64)
    }

    suspend fun exportToFile(
        context: Context,
        recording: RecordingEntity,
        paragraphs: List<ParagraphEntity>,
        format: ExportFormat,
        includeTimestamps: Boolean = true
    ): File = withContext(Dispatchers.IO) {
        val strings = AppLocaleManager.currentStrings()
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "${safeBaseName(recording.title)}.${format.ext}")
        when (format) {
            ExportFormat.TXT -> file.writeText(buildTxt(recording, paragraphs, includeTimestamps, strings), Charsets.UTF_8)
            ExportFormat.SRT -> file.writeText(buildSrt(paragraphs), Charsets.UTF_8)
            ExportFormat.PDF -> writePdf(file, recording, paragraphs, includeTimestamps, strings)
            ExportFormat.DOCX -> writeDocx(file, recording, paragraphs, includeTimestamps, strings)
        }
        file
    }

    suspend fun exportToUri(
        context: Context,
        destUri: Uri,
        recording: RecordingEntity,
        paragraphs: List<ParagraphEntity>,
        format: ExportFormat,
        includeTimestamps: Boolean = true
    ) = withContext(Dispatchers.IO) {
        val tempFile = exportToFile(context, recording, paragraphs, format, includeTimestamps)
        context.contentResolver.openOutputStream(destUri)?.use { out ->
            FileInputStream(tempFile).use { input ->
                input.copyTo(out, 32 * 1024)
            }
            out.flush()
        } ?: throw IllegalStateException(AppLocaleManager.currentStrings().exportError)
    }

    suspend fun copyAudioFileToUri(
        context: Context,
        audioFile: File,
        destUri: Uri
    ) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(destUri)?.use { out ->
            FileInputStream(audioFile).use { input ->
                input.copyTo(out, 64 * 1024)
            }
            out.flush()
        } ?: throw IllegalStateException(AppLocaleManager.currentStrings().exportError)
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
                appendLine("${formatSrtTime(start)} --> ${formatSrtTime(end)}")
                appendLine(text)
                appendLine()
            }
        }
    }

    private fun formatSrtTime(sec: Float): String {
        val msTotal = (sec.coerceAtLeast(0f) * 1000f).toLong()
        val h = msTotal / 3_600_000L
        val m = (msTotal % 3_600_000L) / 60_000L
        val s = (msTotal % 60_000L) / 1000L
        val ms = msTotal % 1000L
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, ms)
    }

    private fun writePdf(
        file: File,
        recording: RecordingEntity,
        paragraphs: List<ParagraphEntity>,
        includeTimestamps: Boolean,
        strings: AppStrings
    ) {
        val pdf = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 46f
        val contentWidth = pageWidth - margin * 2

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 17f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            color = Color.parseColor("#111827")
        }
        val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10f
            color = Color.parseColor("#6B7280")
        }
        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            color = Color.parseColor("#2563EB")
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11.5f
            color = Color.parseColor("#1F2937")
        }
        val linePaint = Paint().apply {
            color = Color.parseColor("#E5E7EB")
            strokeWidth = 1f
        }

        var pageNum = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
        var canvas = page.canvas
        var y = margin + 12f

        fun newPage() {
            canvas.drawText("${strings.docPageShort} $pageNum", pageWidth - margin - 48f, pageHeight - 24f, metaPaint)
            pdf.finishPage(page)
            pageNum++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
            canvas = page.canvas
            y = margin
        }

        fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
            val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return emptyList()
            val lines = mutableListOf<String>()
            var current = StringBuilder()
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(candidate) <= maxWidth) {
                    current = StringBuilder(candidate)
                } else {
                    if (current.isNotEmpty()) lines.add(current.toString())
                    current = StringBuilder(word)
                }
            }
            if (current.isNotEmpty()) lines.add(current.toString())
            return lines
        }

        for (line in wrapText(recording.title, titlePaint, contentWidth)) {
            canvas.drawText(line, margin, y, titlePaint)
            y += 22f
        }
        val dateStr = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(recording.createdAtMs))
        val durStr = AsrTextFormatter.formatDurationMs(recording.durationMs)
        val langStr = TinyAsrCatalog.languageNativeName(recording.detectedLanguage)
        canvas.drawText("$dateStr · ${strings.docMetaDuration}: $durStr · ${strings.docMetaLanguage}: $langStr", margin, y, metaPaint)
        y += 12f
        canvas.drawLine(margin, y, pageWidth - margin, y, linePaint)
        y += 20f

        for (p in paragraphs) {
            if (p.isBookmark && !p.bookmarkLabel.isNullOrBlank()) {
                if (y > pageHeight - margin - 32f) newPage()
                canvas.drawText("⚑ ${p.bookmarkLabel}", margin, y, timePaint)
                y += 14f
            }
            if (includeTimestamps || !p.speaker.isNullOrBlank()) {
                if (y > pageHeight - margin - 32f) newPage()
                val headerLine = buildString {
                    if (includeTimestamps) append(AsrTextFormatter.formatTimestampSec(p.startSec))
                    if (!p.speaker.isNullOrBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(p.speaker)
                    }
                }
                canvas.drawText(headerLine, margin, y, timePaint)
                y += 14f
            }
            val lines = wrapText(p.text, bodyPaint, contentWidth)
            for (line in lines) {
                if (y > pageHeight - margin - 18f) newPage()
                canvas.drawText(line, margin, y, bodyPaint)
                y += 16f
            }
            y += 10f
        }

        canvas.drawText("${strings.docPageShort} $pageNum", pageWidth - margin - 48f, pageHeight - 24f, metaPaint)
        pdf.finishPage(page)

        FileOutputStream(file).use { out ->
            pdf.writeTo(out)
        }
        pdf.close()
    }

    private fun writeDocx(
        file: File,
        recording: RecordingEntity,
        paragraphs: List<ParagraphEntity>,
        includeTimestamps: Boolean,
        strings: AppStrings
    ) {
        fun escXml(s: String): String = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        val dateStr = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(recording.createdAtMs))
        val durStr = AsrTextFormatter.formatDurationMs(recording.durationMs)
        val langStr = TinyAsrCatalog.languageNativeName(recording.detectedLanguage)

        val bodyXml = buildString {
            append("""<w:p><w:pPr><w:spacing w:after="120"/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val="32"/></w:rPr><w:t>${escXml(recording.title)}</w:t></w:r></w:p>""")
            append("""<w:p><w:pPr><w:spacing w:after="240"/></w:pPr><w:r><w:rPr><w:color w:val="6B7280"/><w:sz w:val="20"/></w:rPr><w:t>${escXml("$dateStr · ${strings.docMetaDuration}: $durStr · ${strings.docMetaLanguage}: $langStr")}</w:t></w:r></w:p>""")

            for (p in paragraphs) {
                if (p.isBookmark && !p.bookmarkLabel.isNullOrBlank()) {
                    append("""<w:p><w:pPr><w:spacing w:before="120" w:after="40"/></w:pPr><w:r><w:rPr><w:b/><w:color w:val="2563EB"/><w:sz w:val="20"/></w:rPr><w:t>${escXml("⚑ ${p.bookmarkLabel}")}</w:t></w:r></w:p>""")
                }
                append("""<w:p><w:pPr><w:spacing w:after="160" w:line="300" w:lineRule="auto"/></w:pPr>""")
                if (includeTimestamps) {
                    append("""<w:r><w:rPr><w:b/><w:color w:val="2563EB"/><w:sz w:val="20"/></w:rPr><w:t xml:space="preserve">[${escXml(AsrTextFormatter.formatTimestampSec(p.startSec))}] </w:t></w:r>""")
                }
                if (!p.speaker.isNullOrBlank()) {
                    append("""<w:r><w:rPr><w:b/><w:sz w:val="22"/></w:rPr><w:t xml:space="preserve">${escXml(p.speaker)}: </w:t></w:r>""")
                }
                append("""<w:r><w:rPr><w:sz w:val="23"/></w:rPr><w:t xml:space="preserve">${escXml(p.text)}</w:t></w:r></w:p>""")
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
