package com.wavlin.music.ui.kit.flutter

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Compiled when the Flutter AAR is not part of the build; callers use the Compose versions. */
@Suppress("UNUSED_PARAMETER")
object FlutterVisuals {
    fun isAvailable(context: Context): Boolean = false

    fun acquireWrappedBackground(context: Context, token: Any): Boolean = false

    fun releaseWrappedBackground(token: Any) = Unit
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun FlutterReverbVisualizer(
    presetIndex: Int,
    enabled: Boolean,
    speed: Float,
    modifier: Modifier = Modifier,
) = Unit

@Suppress("UNUSED_PARAMETER")
@Composable
fun FlutterWrappedBackground(
    elementCount: Int,
    shapes: List<String>,
    seed: Long,
    modifier: Modifier = Modifier,
) = Unit
