package com.wavlin.music.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.wavlin.music.ui.kit.flutter.FlutterReverbVisualizer
import com.wavlin.music.ui.kit.flutter.FlutterVisuals

/** Shows the Flutter visualizer when the Flutter engine is available, else the Compose one. */
@Composable
fun ReverbRoomVisualizerHost(
    presetIndex: Int,
    enabled: Boolean,
    speed: Float = 1.0f,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val useFlutter = remember { FlutterVisuals.isAvailable(context) }
    if (useFlutter) {
        FlutterReverbVisualizer(presetIndex, enabled, speed, modifier)
    } else {
        ReverbRoomVisualizer(presetIndex, enabled, speed, modifier)
    }
}
