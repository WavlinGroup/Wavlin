/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.pages.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.wavlin.music.R
import com.wavlin.music.ui.style.WavlinSpace
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Greeting at the top of Home: today's date as a small label, then a large time-of-day greeting. */
@Composable
fun HomeGreeting(modifier: Modifier = Modifier) {
    val greetingRes =
        remember {
            when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
                in 5..11 -> R.string.home_greeting_morning
                in 12..17 -> R.string.home_greeting_afternoon
                in 18..21 -> R.string.home_greeting_evening
                else -> R.string.home_greeting_night
            }
        }
    val dateLabel = remember { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()).uppercase() }

    Column(
        verticalArrangement = Arrangement.spacedBy(WavlinSpace.XS),
        modifier =
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = WavlinSpace.Gutter)
                .padding(top = WavlinSpace.M, bottom = WavlinSpace.S),
    ) {
        Text(
            text = dateLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(greetingRes),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
