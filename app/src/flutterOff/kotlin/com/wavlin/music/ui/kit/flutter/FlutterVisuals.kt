package com.wavlin.music.ui.kit.flutter

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Compiled when the Flutter AAR is not part of the build; callers use the Compose visualizer. */
object FlutterVisuals {
    @Suppress("UNUSED_PARAMETER")
    fun isAvailable(context: Context): Boolean = false
}

@Composable
fun FlutterReverbVisualizer(
    presetIndex: Int,
    enabled: Boolean,
    speed: Float,
    modifier: Modifier = Modifier,
) = Unit
