package com.stenograf.app.asr

import java.util.Locale

/**
 * Formats TinyASR word lists and groups committed sentences into logical paragraphs
 * suitable for multi-hour lectures while preserving exact word-level timestamps.
 */
object AsrTextFormatter {

    fun joinWords(words: List<TranscriptWord>): String {
        if (words.isEmpty()) return ""
        val sb = StringBuilder(words.size * 6)
        words.forEachIndexed { index, word ->
            if (index > 0 && needsSpaceBefore(word)) {
                sb.append(' ')
            }
            sb.append(word.text)
        }
        return sb.toString()
    }

    fun needsSpaceBefore(word: TranscriptWord): Boolean =
        !word.join && !word.text.all { it in ",.!?:;%…、。，！？：；،؟" }

    fun isSentenceEnd(wordText: String): Boolean {
        val trimmed = wordText.trimEnd('"', '»', '”', ')', ']')
        if (trimmed.isEmpty()) return false
        val last = trimmed.last()
        return last == '.' || last == '?' || last == '!' || last == '…' ||
            last == '。' || last == '？' || last == '！' || last == '؟' || last == '។'
    }

    fun formatDurationMs(durationMs: Long): String {
        val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    fun formatTimestampSec(sec: Float): String {
        val totalSec = sec.toLong().coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    fun formatSrtTime(sec: Float): String {
        val totalMs = (sec * 1000f).toLong().coerceAtLeast(0L)
        val h = totalMs / 3_600_000L
        val m = (totalMs % 3_600_000L) / 60_000L
        val s = (totalMs % 60_000L) / 1_000L
        val ms = totalMs % 1_000L
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, ms)
    }
}

/**
 * Stateful paragraph accumulator for live sessions and file re-transcription.
 * Splits paragraphs on long pauses (> 2.2s after a sentence), size threshold (~420 chars at sentence end),
 * or explicit user bookmarks.
 */
class ParagraphAccumulator(
    private val autoParagraph: Boolean = true,
    private val pauseThresholdSec: Float = 2.2f,
    private val maxCharsPerParagraph: Int = 420
) {
    data class ParagraphDraft(
        val words: MutableList<TranscriptWord> = mutableListOf(),
        var isBookmark: Boolean = false,
        var bookmarkLabel: String? = null,
        val speaker: String? = null
    ) {
        val startSec: Float get() = words.firstOrNull()?.start ?: 0f
        val endSec: Float get() = words.lastOrNull()?.end ?: 0f
        val text: String get() = AsrTextFormatter.joinWords(words)
    }

    data class CommitUpdate(
        val updatedLastParagraph: ParagraphDraft?,
        val newParagraph: ParagraphDraft?
    )

    private var current: ParagraphDraft? = null
    private var forceNewNext: Boolean = false
    private var pendingBookmarkLabel: String? = null

    fun restoreCurrent(
        words: List<TranscriptWord>,
        isBookmark: Boolean = false,
        bookmarkLabel: String? = null,
        speaker: String? = null
    ) {
        current = ParagraphDraft(words.toMutableList(), isBookmark, bookmarkLabel, speaker)
    }

    fun requestParagraphBreak(bookmarkLabel: String? = null) {
        forceNewNext = true
        pendingBookmarkLabel = bookmarkLabel
    }

    fun acceptCommit(words: List<TranscriptWord>, speaker: String? = null): CommitUpdate {
        if (words.isEmpty()) return CommitUpdate(null, null)

        val cur = current
        if (cur == null || cur.words.isEmpty()) {
            val draft = ParagraphDraft(
                words = words.toMutableList(),
                isBookmark = pendingBookmarkLabel != null,
                bookmarkLabel = pendingBookmarkLabel,
                speaker = speaker
            )
            pendingBookmarkLabel = null
            forceNewNext = false
            current = draft
            return CommitUpdate(null, draft)
        }

        val lastWord = cur.words.last()
        val firstNew = words.first()
        val gapSec = firstNew.start - lastWord.end
        val curTextLen = cur.text.length
        val endedSentence = AsrTextFormatter.isSentenceEnd(lastWord.text)

        val shouldSplit = forceNewNext || cur.speaker != speaker || (
            autoParagraph && endedSentence && (
                gapSec >= pauseThresholdSec ||
                    curTextLen >= maxCharsPerParagraph
                )
            )

        return if (shouldSplit) {
            val draft = ParagraphDraft(
                words = words.toMutableList(),
                isBookmark = pendingBookmarkLabel != null,
                bookmarkLabel = pendingBookmarkLabel,
                speaker = speaker
            )
            pendingBookmarkLabel = null
            forceNewNext = false
            current = draft
            CommitUpdate(null, draft)
        } else {
            cur.words.addAll(words)
            CommitUpdate(cur, null)
        }
    }

    fun reset() {
        current = null
        forceNewNext = false
        pendingBookmarkLabel = null
    }
}

object AsrWindowMerger {
    private fun normalizeToken(text: String): String =
        text.lowercase().trim { !it.isLetterOrDigit() }

    /**
     * Filters out sliding-window boundary duplicates when slightly shifted timestamps cause a trailing
     * committed word sequence to reappear past [coveredUntilSec].
     */
    fun trimOverlapWords(
        words: List<TranscriptWord>,
        coveredUntilSec: Float,
        recentEmittedWords: List<TranscriptWord>
    ): List<TranscriptWord> {
        if (words.isEmpty()) return emptyList()
        var idx = 0
        while (idx < words.size) {
            val w = words[idx]
            val mid = (w.start + w.end) * 0.5f
            if (mid < coveredUntilSec - 0.03f) {
                idx++
            } else {
                break
            }
        }
        val tail = recentEmittedWords.takeLast(4)
        if (idx < words.size && tail.isNotEmpty()) {
            val remaining = words.size - idx
            val maxK = minOf(tail.size, remaining)
            var matched = 0
            for (k in maxK downTo 1) {
                val tailSlice = tail.subList(tail.size - k, tail.size)
                val newSlice = words.subList(idx, idx + k)
                val allMatch = tailSlice.zip(newSlice).all { (oldW, newW) ->
                    val oldKey = normalizeToken(oldW.text)
                    val newKey = normalizeToken(newW.text)
                    val oldMid = (oldW.start + oldW.end) * 0.5f
                    val newMid = (newW.start + newW.end) * 0.5f
                    oldKey.isNotEmpty() && oldKey == newKey && kotlin.math.abs(newMid - oldMid) <= 0.65f
                }
                if (allMatch) {
                    matched = k
                    break
                }
            }
            idx += matched
        }
        return if (idx <= 0) words else if (idx >= words.size) emptyList() else words.subList(idx, words.size)
    }
}
