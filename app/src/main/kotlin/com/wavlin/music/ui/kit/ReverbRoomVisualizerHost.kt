package com.wavlin.music.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import com.wavlin.music.ui.kit.flutter.FlutterReverbVisualizer
import com.wavlin.music.ui.kit.flutter.FlutterVisuals
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private const val CURTAIN_HOLD_MS = 250L

/**
 * Shows the Flutter visualizer when the Flutter engine is available, else the Compose one.
 *
 * The Flutter view is a TextureView and lags behind its card while the sheet is dragged, animates
 * or scrolls, which showed the player behind the sheet inside the visualizer. So the Compose
 * visualizer is drawn over it from the first moved frame until shortly after the position settles.
 * The Flutter view stays attached underneath, so nothing is torn down and rebuilt.
 */
@Composable
fun ReverbRoomVisualizerHost(
    presetIndex: Int,
    enabled: Boolean,
    speed: Float = 1.0f,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val useFlutter = remember { FlutterVisuals.isAvailable(context) }
    if (!useFlutter) {
        ReverbRoomVisualizer(presetIndex, enabled, speed, modifier)
        return
    }

    var curtain by remember { mutableStateOf(true) }
    var moveStamp by remember { mutableLongStateOf(0L) }
    val lastPosition = remember { arrayOf(Offset.Unspecified) }
    LaunchedEffect(Unit) {
        snapshotFlow { moveStamp }.collectLatest {
            curtain = true
            delay(CURTAIN_HOLD_MS)
            curtain = false
        }
    }
    Box(
        modifier.onGloballyPositioned { coordinates ->
            val position = coordinates.positionInWindow()
            if (position != lastPosition[0]) {
                lastPosition[0] = position
                moveStamp++
            }
        },
    ) {
        FlutterReverbVisualizer(presetIndex, enabled, speed, Modifier)
        if (curtain) {
            ReverbRoomVisualizer(presetIndex, enabled, speed, Modifier)
        }
    }
}
