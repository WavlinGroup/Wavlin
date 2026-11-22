package com.wavlin.music.ui.kit.flutter

import android.content.Context
import android.graphics.Color as AndroidColor
import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.flutter.embedding.android.FlutterTextureView
import io.flutter.embedding.android.FlutterView
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel

private const val TAG = "FlutterVisuals"
private const val CHANNEL = "com.wavlin.visuals/reverb"

/** One shared engine running wavlin_visuals' main(); created on first use. */
object FlutterVisuals {
    private var engine: FlutterEngine? = null
    private var channel: MethodChannel? = null
    private var initFailed = false
    private var lastState: Map<String, Any>? = null

    /** True when the engine started. Call from the main thread. */
    fun isAvailable(context: Context): Boolean = ensureEngine(context) != null

    internal fun ensureEngine(context: Context): FlutterEngine? {
        engine?.let { return it }
        if (initFailed) return null
        return try {
            val created = FlutterEngine(context.applicationContext)
            val methods = MethodChannel(created.dartExecutor.binaryMessenger, CHANNEL)
            methods.setMethodCallHandler { call, result ->
                if (call.method == "ready") {
                    lastState?.let { methods.invokeMethod("update", it) }
                    result.success(null)
                } else {
                    result.notImplemented()
                }
            }
            created.dartExecutor.executeDartEntrypoint(DartExecutor.DartEntrypoint.createDefault())
            engine = created
            channel = methods
            created
        } catch (t: Throwable) {
            Log.e(TAG, "Flutter engine failed to start, using the Compose visualizer", t)
            initFailed = true
            null
        }
    }

    internal fun send(state: Map<String, Any>) {
        lastState = state
        channel?.invokeMethod("update", state)
    }
}

@Composable
fun FlutterReverbVisualizer(
    presetIndex: Int,
    enabled: Boolean,
    speed: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val primary = scheme.primary.toArgb()
    val secondary = scheme.secondary.toArgb()
    val tertiary = scheme.tertiary.toArgb()
    val muted = scheme.onSurfaceVariant.toArgb()

    LaunchedEffect(presetIndex, enabled, speed, primary, secondary, tertiary, muted) {
        FlutterVisuals.send(
            mapOf(
                "presetIndex" to presetIndex,
                "enabled" to enabled,
                "speed" to speed.toDouble(),
                "primary" to primary,
                "secondary" to secondary,
                "tertiary" to tertiary,
                "muted" to muted,
            ),
        )
    }

    val attached = remember { arrayOfNulls<FlutterView>(1) }
    DisposableEffect(Unit) {
        onDispose {
            attached[0]?.let { view ->
                FlutterVisuals.ensureEngine(context)?.lifecycleChannel?.appIsPaused()
                view.detachFromFlutterEngine()
            }
            attached[0] = null
        }
    }

    AndroidView(
        modifier = modifier.fillMaxWidth().height(130.dp),
        factory = { ctx ->
            FlutterView(ctx, FlutterTextureView(ctx)).also { view ->
                view.setBackgroundColor(AndroidColor.TRANSPARENT)
                FlutterVisuals.ensureEngine(ctx)?.let { engine ->
                    view.attachToFlutterEngine(engine)
                    engine.lifecycleChannel.appIsResumed()
                    attached[0] = view
                }
            }
        },
    )
}
