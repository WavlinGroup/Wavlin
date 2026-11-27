/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.kit

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wavlin.music.ui.style.WavlinSpace

/** Top bar title of the Wavlin design: a large title led by a small three-bar wave mark. */
@Composable
fun WavlinTitle(text: String) {
    val accent = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier
                .size(width = 16.dp, height = 22.dp)
                .drawBehind {
                    val barWidth = 4.dp.toPx()
                    val step = 6.dp.toPx()
                    listOf(0.55f, 1f, 0.75f).forEachIndexed { index, fraction ->
                        val barHeight = size.height * fraction
                        drawRoundRect(
                            color = accent,
                            topLeft = Offset(index * step, (size.height - barHeight) / 2f),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(barWidth / 2f),
                        )
                    }
                },
        )
        Spacer(Modifier.width(WavlinSpace.M))
        Text(
            text = text,
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
