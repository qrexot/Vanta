package com.stenograf.app.export

import org.junit.Assert.assertEquals
import org.junit.Test
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

class TranscriptExporterTest {
    @Test
    fun filenameHandlesPathCharactersAndTruncatedEmoji() {
        assertEquals("Lecture_ Week_1", TranscriptExporter.safeBaseName(" Lecture: Week/1. "))
        assertEquals("recording", TranscriptExporter.safeBaseName(".."))
        val prefix = "a".repeat(63)
        assertEquals(prefix, TranscriptExporter.safeBaseName(prefix + "🎙️"))
    }

    @Test
    fun wordXmlPreservesBreaksTabsAndSupplementaryCharacters() {
        val text = "Текст <&>\r\n\tعربي 🎙\u0000"
        val xml = """<w:r xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
            <w:t xml:space="preserve">${TranscriptExporter.docxText(text)}</w:t></w:r>"""
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
        assertEquals(1, document.getElementsByTagName("w:br").length)
        assertEquals(1, document.getElementsByTagName("w:tab").length)
        val nodes = document.getElementsByTagName("w:t")
        assertEquals("Текст <&>", nodes.item(0).textContent)
        assertEquals("عربي 🎙", nodes.item(2).textContent)
    }
}
