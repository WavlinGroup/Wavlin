/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

private const val TickMs = 5_000f
private const val TicksPerMajor = 6
private val TickSpacing = 10.dp

/**
 * Dial-style seek bar: a ruler of five second ticks (a tall one every thirty seconds) that slides under
 * a fixed needle. Drag to scrub, tap a tick to jump to it. Same value contract as a Slider.
 */
@Composable
fun RulerSeekBar(
    value: Float,
    duration: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    color: Color,
    needleColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val end = duration.coerceAtLeast(0f)
    val position = value.coerceIn(0f, end)
    // Time runs right to left in RTL layouts, like the Material slider.
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val currentPosition by rememberUpdatedState(position)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
    val interactive = enabled && end > 0f

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(position, 0f..end)
                if (interactive) {
                    setProgress { target ->
                        currentOnChange(target.coerceIn(0f, end))
                        currentOnFinished()
                        true
                    }
                }
            }
            .pointerInput(interactive, end, direction) {
                if (!interactive) return@pointerInput
                val msPerPx = TickMs / TickSpacing.toPx()
                var target = 0f
                detectHorizontalDragGestures(
                    onDragStart = { target = currentPosition },
                    onDragEnd = { currentOnFinished() },
                    onDragCancel = { currentOnFinished() },
                ) { change, dragAmount ->
                    change.consume()
                    target = (target - direction * dragAmount * msPerPx).coerceIn(0f, end)
                    currentOnChange(target)
                }
            }
            .pointerInput(interactive, end, direction) {
                if (!interactive) return@pointerInput
                val msPerPx = TickMs / TickSpacing.toPx()
                detectTapGestures { offset ->
                    val delta = direction * (offset.x - size.width / 2f) * msPerPx
                    currentOnChange((currentPosition + delta).coerceIn(0f, end))
                    currentOnFinished()
                }
            },
    ) {
        val middle = size.width / 2f
        val spacing = TickSpacing.toPx()
        val pxPerMs = spacing / TickMs
        val visibleMs = middle / pxPerMs
        val first = floor((position - visibleMs) / TickMs).toInt().coerceAtLeast(0)
        val last = ceil((position + visibleMs) / TickMs).toInt().coerceAtMost((end / TickMs).toInt())

        for (index in first..last) {
            val tickMs = index * TickMs
            val x = middle + direction * (tickMs - position) * pxPerMs
            val distance = (abs(x - middle) / middle).coerceIn(0f, 1f)
            val edgeFade = 1f - distance * distance
            val major = index % TicksPerMajor == 0
            val half = (if (major) 11.dp else 6.dp).toPx()
            val base = if (tickMs <= position) 1f else 0.4f
            drawLine(
                color = color.copy(alpha = color.alpha * base * edgeFade),
                start = Offset(x, center.y - half),
                end = Offset(x, center.y + half),
                strokeWidth = (if (major) 2.5.dp else 2.dp).toPx(),
                cap = StrokeCap.Round,
            )
        }

        drawLine(
            color = needleColor,
            start = Offset(middle, center.y - 19.dp.toPx()),
            end = Offset(middle, center.y + 19.dp.toPx()),
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}
