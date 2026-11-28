/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.pages.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wavlin.music.BuildConfig
import com.wavlin.music.R
import com.wavlin.music.ui.kit.LocalInterfaceAppleMusic
import com.wavlin.music.ui.style.LocalWavlinLayout
/** App identity card at the top of Settings: logo, name and version, opens the About page. */
@Composable
fun SettingsHeroCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appleMusic = LocalInterfaceAppleMusic.current
    val colors = MaterialTheme.colorScheme
    val wavlin = LocalWavlinLayout.current
    val shape = RoundedCornerShape(if (wavlin) 20.dp else if (appleMusic) 18.dp else 28.dp)
    val fg = if (wavlin) colors.onSurface else colors.onPrimaryContainer
    val iconTint = if (wavlin) colors.primary else colors.onPrimaryContainer
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .then(
                if (wavlin) {
                    Modifier.background(colors.surfaceContainerLow)
                } else {
                    Modifier.background(Brush.linearGradient(listOf(colors.primaryContainer, colors.tertiaryContainer)))
                },
            )
            .then(
                if (wavlin) {
                    Modifier.border(BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f)), shape)
                } else if (appleMusic) {
                    Modifier.border(BorderStroke(0.5.dp, colors.outlineVariant), shape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(20.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .background(fg.copy(alpha = 0.12f), CircleShape),
        ) {
            Icon(
                painter = painterResource(R.drawable.music_note),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = fg,
            )
            Text(
                text = stringResource(R.string.version_format, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
                color = fg.copy(alpha = 0.8f),
            )
        }
        Icon(
            painter = painterResource(R.drawable.navigate_next),
            contentDescription = null,
            tint = fg.copy(alpha = 0.7f),
        )
    }
}
