/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.annotation.DrawableRes
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.wavlin.music.ui.style.LocalWavlinLayout
import com.wavlin.music.ui.style.PinkInk
import com.wavlin.music.ui.style.WavlinSpace
import com.wavlin.music.ui.style.playball
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wavlin.music.R

@Composable
fun NavigationTitle(
    title: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    thumbnail: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onPlayAllClick: (() -> Unit)? = null,
    @DrawableRes leadingIconRes: Int? = null,
) {
    val pinkLily = LocalPinkLilyTheme.current
    val appleMusic = LocalInterfaceAppleMusic.current && !pinkLily
    val wavlinLayout = LocalWavlinLayout.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
            .clickable(enabled = onClick != null) {
                onClick?.invoke()
            }
            .padding(
                horizontal = if (wavlinLayout) WavlinSpace.Gutter else 12.dp,
                vertical = if (wavlinLayout) WavlinSpace.M else 12.dp,
            )
    ) {
        if (pinkLily && leadingIconRes != null) {
            Icon(
                painter = painterResource(leadingIconRes),
                contentDescription = null,
                tint = PinkInk,
                modifier = Modifier.size(24.dp),
            )
        }
        thumbnail?.invoke()

        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.weight(1f)
        ) {
            label?.let { label ->
                Text(
                    text = if (wavlinLayout) label.uppercase() else label,
                    style = if (wavlinLayout) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                    color = if (wavlinLayout) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (pinkLily) {
                Text(
                    text = title,
                    fontFamily = playball,
                    fontSize = 30.sp,
                    lineHeight = 36.sp,
                    color = PinkInk,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
            } else {
                Text(
                    text = title,
                    style = if (wavlinLayout) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (appleMusic || wavlinLayout) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
            }
        }

        onPlayAllClick?.let { playAllClick ->
            OutlinedButton(
                onClick = playAllClick,
                shape = if (appleMusic) RoundedCornerShape(percent = 50) else RoundedCornerShape(12.dp),
                border = BorderStroke(if (appleMusic) 0.dp else 1.dp, MaterialTheme.colorScheme.primary.copy(alpha = if (appleMusic) 0f else 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                modifier = Modifier
                    .height(if (wavlinLayout) 32.dp else 24.dp)
            ) {
                Text(
                    text = stringResource(R.string.play_all),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        if (onClick != null) {
            Icon(
                painter = painterResource(if (pinkLily || appleMusic) R.drawable.navigate_next else R.drawable.arrow_forward),
                contentDescription = null,
                tint = if (pinkLily) PinkInk else if (appleMusic) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
            )
        }
    }
}
