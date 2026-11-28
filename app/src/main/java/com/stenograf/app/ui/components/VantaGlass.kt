package com.stenograf.app.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.stenograf.app.ui.theme.LocalStenografColors
import com.stenograf.app.ui.theme.Spacing
import com.stenograf.app.ui.theme.VantaType
import com.stenograf.app.ui.theme.pressScale
import com.stenograf.app.ui.theme.rememberVantaHaptics
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

@Composable
internal fun GlassScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        modifier = modifier,
        containerColor = LocalStenografColors.current.bg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = topBar,
        bottomBar = bottomBar,
        content = content
    )
}

@Composable
internal fun Modifier.vantaGlass(
    shape: Shape = RoundedCornerShape(16.dp)
): Modifier {
    val colors = LocalStenografColors.current
    val glassBg = if (colors.isDark) {
        Color(0xEB222D3B)
    } else {
        Color(0xF0FFFFFF)
    }
    val rimBrush = if (colors.isDark) {
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.04f))
        )
    } else {
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.85f), Color(0x160F172A))
        )
    }
    return this
        .shadow(elevation = 6.dp, shape = shape, clip = false)
        .clip(shape)
        .background(glassBg)
        .border(0.6.dp, rimBrush, shape)
}

@Immutable
internal data class LiquidGlassTab(
    val label: String,
    val icon: ImageVector
)

/**
 * Telegram-style Liquid Glass floating navigation dock.
 * - Real hardware-accelerated backdrop blur inside the capsule on Android 12+ ([backdropLayer]).
 * - Smooth two-edge elastic droplet inertia ([leadPos] + exponential [trailPos]) that runs
 *   on-demand only during motion and draws purely in the draw phase without per-frame recomposition.
 * - Zero Material ripple flash on tab tap.
 */
@Composable
internal fun VantaLiquidGlassDock(
    tabs: List<LiquidGlassTab>,
    pagePositionProvider: () -> Float,
    settledIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    backdropLayer: GraphicsLayer? = null,
    backdropOriginProvider: () -> Offset = { Offset.Zero }
) {
    if (tabs.isEmpty()) return
    val colors = LocalStenografColors.current
    val haptics = rememberVantaHaptics()
    val capsuleShape = RoundedCornerShape(28.dp)
    val maxIdx = (tabs.size - 1).toFloat().coerceAtLeast(0f)

    val leadPos = remember { mutableFloatStateOf(settledIndex.toFloat().coerceIn(0f, maxIdx)) }
    val trailPos = remember { mutableFloatStateOf(settledIndex.toFloat().coerceIn(0f, maxIdx)) }

    LaunchedEffect(pagePositionProvider, maxIdx) {
        snapshotFlow { pagePositionProvider().coerceIn(0f, maxIdx) }.collectLatest { target ->
            leadPos.floatValue = target
            if (abs(trailPos.floatValue - target) < 0.0008f) {
                trailPos.floatValue = target
                return@collectLatest
            }
            var lastNanos = 0L
            while (abs(trailPos.floatValue - leadPos.floatValue) >= 0.0008f) {
                withFrameNanos { now ->
                    val dt = if (lastNanos == 0L) 0.016f
                    else ((now - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.032f)
                    lastNanos = now
                    val alpha = 1f - exp(-dt * 24f)
                    trailPos.floatValue += (leadPos.floatValue - trailPos.floatValue) * alpha
                }
            }
            trailPos.floatValue = leadPos.floatValue
        }
    }

    val canBlurBackdrop = backdropLayer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val blurLayer = if (canBlurBackdrop) rememberGraphicsLayer() else null
    var dockOriginInRoot by remember { mutableStateOf(Offset.Zero) }

    val dockFrostFill = remember(colors.isDark, canBlurBackdrop) {
        when {
            canBlurBackdrop && colors.isDark -> Brush.verticalGradient(
                listOf(Color(0xBA1E2834), Color(0xC217202B))
            )
            canBlurBackdrop && !colors.isDark -> Brush.verticalGradient(
                listOf(Color(0xCCFFFFFF), Color(0xC4F4F7FA))
            )
            colors.isDark -> Brush.verticalGradient(
                listOf(Color(0xE8212C3A), Color(0xE019222D))
            )
            else -> Brush.verticalGradient(
                listOf(Color(0xF2FFFFFF), Color(0xE8F5F7FA))
            )
        }
    }
    val dockRim = remember(colors.isDark) {
        if (colors.isDark) {
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.035f))
            )
        } else {
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.92f), Color(0x160F172A))
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.xl, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .onGloballyPositioned { coords ->
                    dockOriginInRoot = coords.positionInRoot()
                }
                .shadow(
                    elevation = if (colors.isDark) 10.dp else 8.dp,
                    shape = capsuleShape,
                    clip = false,
                    ambientColor = if (colors.isDark) Color.Black.copy(alpha = 0.36f) else Color(0x140F172A),
                    spotColor = if (colors.isDark) Color.Black.copy(alpha = 0.44f) else Color(0x1C0F172A)
                )
                .clip(capsuleShape)
                .drawBehind {
                    // 1. Real blurred backdrop slice (Android 12+)
                    if (blurLayer != null && backdropLayer != null &&
                        backdropLayer.size.width > 0 && backdropLayer.size.height > 0
                    ) {
                        val w = size.width.roundToInt()
                        val h = size.height.roundToInt()
                        if (w > 0 && h > 0) {
                            val rel = dockOriginInRoot - backdropOriginProvider()
                            val blurPx = 18.dp.toPx()
                            blurLayer.clip = true
                            blurLayer.renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
                            blurLayer.record(size = IntSize(w, h)) {
                                translate(-rel.x, -rel.y) {
                                    drawLayer(backdropLayer)
                                }
                            }
                            drawLayer(blurLayer)
                        }
                    }

                    // 2. Clean frosted glass surface tint
                    drawRect(brush = dockFrostFill)

                    // 3. Fluid two-edge Liquid Glass droplet indicator
                    val count = tabs.size
                    val innerPad = 4.dp.toPx()
                    val trackWidth = (size.width - innerPad * 2f).coerceAtLeast(1f)
                    val slotWidth = trackWidth / count
                    val baseW = (slotWidth - 4.dp.toPx()).coerceAtLeast(24.dp.toPx())
                    val baseH = (size.height - 8.dp.toPx()).coerceAtLeast(24.dp.toPx())

                    val lead = leadPos.floatValue.coerceIn(0f, maxIdx)
                    val trail = (lead + (trailPos.floatValue - lead).coerceIn(-0.34f, 0.34f))
                        .coerceIn(0f, maxIdx)
                    val minP = minOf(lead, trail)
                    val maxP = maxOf(lead, trail)

                    val rawLeft = innerPad + (minP + 0.5f) * slotWidth - baseW / 2f
                    val rawRight = innerPad + (maxP + 0.5f) * slotWidth + baseW / 2f
                    val pillLeft = rawLeft.coerceIn(innerPad, (size.width - innerPad - baseW).coerceAtLeast(innerPad))
                    val pillRight = rawRight.coerceIn(pillLeft + baseW, (size.width - innerPad).coerceAtLeast(pillLeft + baseW))
                    val pillW = pillRight - pillLeft
                    val stretch = ((pillW / baseW) - 1f).coerceIn(0f, 0.45f)
                    val pillH = baseH * (1f - 0.08f * stretch)
                    val pillTop = (size.height - pillH) / 2f
                    val pillRadius = CornerRadius(pillH / 2f, pillH / 2f)

                    if (!colors.isDark) {
                        // Subtle separation shadow under light glass lens
                        drawRoundRect(
                            color = Color(0x0E0F172A),
                            topLeft = Offset(pillLeft, pillTop + 1.dp.toPx()),
                            size = Size(pillW, pillH),
                            cornerRadius = pillRadius
                        )
                    }

                    val dropletFill = if (colors.isDark) {
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.11f + 0.03f * stretch),
                                Color.White.copy(alpha = 0.055f)
                            ),
                            startY = pillTop,
                            endY = pillTop + pillH
                        )
                    } else {
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.90f),
                                Color(0xFFF1F5F9).copy(alpha = 0.74f)
                            ),
                            startY = pillTop,
                            endY = pillTop + pillH
                        )
                    }
                    drawRoundRect(
                        brush = dropletFill,
                        topLeft = Offset(pillLeft, pillTop),
                        size = Size(pillW, pillH),
                        cornerRadius = pillRadius
                    )

                    val dropletStroke = if (colors.isDark) {
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.20f + 0.06f * stretch),
                                Color.White.copy(alpha = 0.035f)
                            ),
                            startY = pillTop,
                            endY = pillTop + pillH
                        )
                    } else {
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White,
                                Color(0x160F172A)
                            ),
                            startY = pillTop,
                            endY = pillTop + pillH
                        )
                    }
                    drawRoundRect(
                        brush = dropletStroke,
                        topLeft = Offset(pillLeft, pillTop),
                        size = Size(pillW, pillH),
                        cornerRadius = pillRadius,
                        style = Stroke(width = 0.6.dp.toPx())
                    )
                }
                .border(0.5.dp, dockRim, capsuleShape)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            tabs.forEachIndexed { index, tab ->
                val interactionSource = remember { MutableInteractionSource() }
                val focus by remember(index, tabs.size) {
                    derivedStateOf {
                        (1f - abs(pagePositionProvider().coerceIn(0f, maxIdx) - index)).coerceIn(0f, 1f)
                    }
                }
                val itemColor = lerp(colors.textSecondary, colors.accent, focus)

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pressScale(pressedScale = 0.94f)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Tab,
                            onClick = {
                                haptics.tick()
                                onSelect(index)
                            }
                        )
                        .semantics { this.selected = settledIndex == index },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = null,
                        tint = itemColor,
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer {
                                val f = (1f - abs(pagePositionProvider().coerceIn(0f, maxIdx) - index))
                                    .coerceIn(0f, 1f)
                                val sc = 1f + 0.04f * f
                                scaleX = sc
                                scaleY = sc
                            }
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = tab.label,
                        color = itemColor,
                        style = VantaType.monoBadge.copy(
                            fontWeight = if (focus >= 0.5f) FontWeight.SemiBold else FontWeight.Medium
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
