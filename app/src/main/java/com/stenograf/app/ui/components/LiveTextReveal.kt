package com.stenograf.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.stenograf.app.ui.theme.Motion

private const val InitialOpacity = 0.26f

/** Fades newly written or corrected text while keeping the existing words at full opacity. */
@Composable
internal fun rememberLiveTextReveal(
    source: AnnotatedString,
    color: Color,
    animateInitial: Boolean = false
): AnnotatedString {
    val spokenText = source.text
    var revealedText by remember {
        mutableStateOf(if (animateInitial) RevealedText("", 0) else RevealedText(spokenText, spokenText.length))
    }
    val opacity = remember { Animatable(1f) }

    LaunchedEffect(spokenText) {
        val boundary = revealStart(revealedText.text, spokenText)
        val hasNewText = boundary < spokenText.length
        opacity.snapTo(if (hasNewText) InitialOpacity else 1f)
        revealedText = RevealedText(spokenText, boundary)
        if (hasNewText) opacity.animateTo(1f, Motion.mediumTween())
    }

    val pendingChange = revealedText.text != spokenText
    val alpha = if (pendingChange) InitialOpacity else opacity.value
    val from = if (pendingChange) revealStart(revealedText.text, spokenText) else revealedText.start
    if (alpha >= 1f || from >= spokenText.length) return source

    fun Color.faded() = if (this == Color.Unspecified) this else copy(alpha = this.alpha * alpha)
    return buildAnnotatedString {
        append(source)
        addStyle(SpanStyle(color = color.faded()), from, spokenText.length)
        for (span in source.spanStyles) {
            val first = maxOf(span.start, from)
            val last = minOf(span.end, spokenText.length)
            if (first < last) {
                addStyle(span.item.copy(color = span.item.color.faded(), background = span.item.background.faded()), first, last)
            }
        }
    }
}

private data class RevealedText(val text: String, val start: Int)

internal fun revealStart(previous: String, current: String): Int {
    var prefix = previous.commonPrefixWith(current).length
    if (prefix == current.length || prefix == previous.length) return prefix
    while (prefix > 0 && !current[prefix - 1].isWhitespace()) prefix--
    return prefix
}
