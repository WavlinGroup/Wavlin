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

/**
 * One FlutterEngine running one Dart entrypoint, created on first use. An engine can drive only
 * one FlutterView at a time, so views take it with [acquire] and give it back with [release].
 * Main thread only.
 */
internal class VisualEngine(
    private val entrypoint: String?,
    private val channelName: String,
) {
    private var engine: FlutterEngine? = null
    private var channel: MethodChannel? = null
    private var initFailed = false
    private var lastState: Map<String, Any>? = null
    private var owner: Any? = null

    fun ensureEngine(context: Context): FlutterEngine? {
        engine?.let { return it }
        if (initFailed) return null
        return try {
            val created = FlutterEngine(context.applicationContext)
            val methods = MethodChannel(created.dartExecutor.binaryMessenger, channelName)
            methods.setMethodCallHandler { call, result ->
                if (call.method == "ready") {
                    lastState?.let { methods.invokeMethod("update", it) }
                    result.success(null)
                } else {
                    result.notImplemented()
                }
            }
            val dartEntrypoint =
                if (entrypoint == null) {
                    DartExecutor.DartEntrypoint.createDefault()
                } else {
                    DartExecutor.DartEntrypoint(
                        io.flutter.FlutterInjector.instance().flutterLoader().findAppBundlePath(),
                        entrypoint,
                    )
                }
            created.dartExecutor.executeDartEntrypoint(dartEntrypoint)
            engine = created
            channel = methods
            created
        } catch (t: Throwable) {
            Log.e(TAG, "Flutter engine '$channelName' failed to start, using the Compose version", t)
            initFailed = true
            null
        }
    }

    fun send(state: Map<String, Any>) {
        lastState = state
        channel?.invokeMethod("update", state)
    }

    fun acquire(token: Any): Boolean {
        if (owner == null || owner === token) {
            owner = token
            return true
        }
        return false
    }

    fun release(token: Any) {
        if (owner === token) owner = null
    }
}

object FlutterVisuals {
    private val reverb = VisualEngine(entrypoint = null, channelName = "com.wavlin.visuals/reverb")
    private val wrappedBackground =
        VisualEngine(entrypoint = "wrappedBackgroundMain", channelName = "com.wavlin.visuals/wrapped_background")
    private val eqGraph = VisualEngine(entrypoint = "eqGraphMain", channelName = "com.wavlin.visuals/eq_graph")

    /** True when the reverb engine started. Call from the main thread. */
    fun isAvailable(context: Context): Boolean = reverb.ensureEngine(context) != null

    /** Takes the Wrapped background engine for [token]; false when it is missing or in use. */
    fun acquireWrappedBackground(context: Context, token: Any): Boolean =
        wrappedBackground.ensureEngine(context) != null && wrappedBackground.acquire(token)

    fun releaseWrappedBackground(token: Any) = wrappedBackground.release(token)

    /** Takes the EQ graph engine for [token]; false when it is missing or in use. */
    fun acquireEqGraph(context: Context, token: Any): Boolean =
        eqGraph.ensureEngine(context) != null && eqGraph.acquire(token)

    fun releaseEqGraph(token: Any) = eqGraph.release(token)

    internal fun reverbEngine() = reverb

    internal fun wrappedBackgroundEngine() = wrappedBackground

    internal fun eqGraphEngine() = eqGraph
}

/** Hosts [visual]'s engine in a transparent FlutterView for as long as this is composed. */
@Composable
private fun FlutterSurface(
    visual: VisualEngine,
    modifier: Modifier,
    opaque: Boolean = true,
) {
    val context = LocalContext.current
    val attached = remember { arrayOfNulls<FlutterView>(1) }
    DisposableEffect(Unit) {
        onDispose {
            attached[0]?.let { view ->
                visual.ensureEngine(context)?.lifecycleChannel?.appIsPaused()
                view.detachFromFlutterEngine()
            }
            attached[0] = null
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            FlutterView(ctx, FlutterTextureView(ctx).also { it.isOpaque = opaque }).also { view ->
                view.setBackgroundColor(AndroidColor.TRANSPARENT)
                visual.ensureEngine(ctx)?.let { engine ->
                    view.attachToFlutterEngine(engine)
                    engine.lifecycleChannel.appIsResumed()
                    attached[0] = view
                }
            }
        },
    )
}

@Composable
fun FlutterReverbVisualizer(
    presetIndex: Int,
    enabled: Boolean,
    speed: Float,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val primary = scheme.primary.toArgb()
    val secondary = scheme.secondary.toArgb()
    val tertiary = scheme.tertiary.toArgb()
    val muted = scheme.onSurfaceVariant.toArgb()
    val visual = FlutterVisuals.reverbEngine()

    LaunchedEffect(presetIndex, enabled, speed, primary, secondary, tertiary, muted) {
        visual.send(
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
    FlutterSurface(visual, modifier.fillMaxWidth().height(130.dp), opaque = false)
}

/** [shapes] are "circle", "rect" or "line". The caller must hold [FlutterVisuals.acquireWrappedBackground]. */
@Composable
fun FlutterWrappedBackground(
    elementCount: Int,
    shapes: List<String>,
    seed: Long,
    modifier: Modifier = Modifier,
) {
    val visual = FlutterVisuals.wrappedBackgroundEngine()
    LaunchedEffect(elementCount, shapes, seed) {
        visual.send(
            mapOf(
                "elementCount" to elementCount,
                "shapes" to shapes,
                "seed" to seed,
            ),
        )
    }
    FlutterSurface(visual, modifier)
}

/**
 * [points] and [markers] are flat (frequencyHz, db) pairs. The caller must hold
 * [FlutterVisuals.acquireEqGraph]. Colors are ARGB ints from Color.toArgb().
 */
@Composable
fun FlutterEqGraph(
    points: DoubleArray,
    markers: DoubleArray,
    dbTop: Double,
    dbStep: Double,
    showBandMarkers: Boolean,
    density: Float,
    fontScale: Float,
    surface: Int,
    grid: Int,
    label: Int,
    curve: Int,
    fill: Int,
    zeroLine: Int,
    markerRing: Int,
    modifier: Modifier = Modifier,
) {
    val visual = FlutterVisuals.eqGraphEngine()
    LaunchedEffect(
        points, markers, dbTop, dbStep, showBandMarkers, density, fontScale,
        surface, grid, label, curve, fill, zeroLine, markerRing,
    ) {
        visual.send(
            mapOf(
                "points" to points,
                "markers" to markers,
                "dbTop" to dbTop,
                "dbStep" to dbStep,
                "showBandMarkers" to showBandMarkers,
                "density" to density.toDouble(),
                "fontScale" to fontScale.toDouble(),
                "surface" to surface,
                "grid" to grid,
                "label" to label,
                "curve" to curve,
                "fill" to fill,
                "zeroLine" to zeroLine,
                "markerRing" to markerRing,
            ),
        )
    }
    FlutterSurface(visual, modifier)
}
