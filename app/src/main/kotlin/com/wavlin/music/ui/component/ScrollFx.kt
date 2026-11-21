/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MaxVelocityPx = 90f
private const val MovingThresholdPx = 1.5f
private const val SettleDelayMs = 60L
private const val SpreadFactor = 0.12f
private const val EnterOffsetDp = 40
private const val EnterScaleFrom = 0.94f
private const val EnterAlphaFrom = 0.3f

/**
 * Smoothed vertical scroll velocity of a list in px per scroll event. Positive means the list is
 * moving toward later items (finger swiping up), negative toward earlier items. Settles to zero
 * shortly after scrolling stops.
 */
@Stable
class ScrollFxState(
    private val scope: CoroutineScope,
) {
    var velocity by mutableFloatStateOf(0f)
        private set

    private var settleJob: Job? = null

    val connection: NestedScrollConnection =
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val delta = -available.y
                if (delta != 0f) {
                    velocity = (velocity * 0.5f + delta * 0.5f).coerceIn(-MaxVelocityPx, MaxVelocityPx)
                    settleJob?.cancel()
                    settleJob =
                        scope.launch {
                            delay(SettleDelayMs)
                            animate(
                                initialValue = velocity,
                                targetValue = 0f,
                                animationSpec = spring(stiffness = Spring.StiffnessLow),
                            ) { value, _ -> velocity = value }
                        }
                }
                return Offset.Zero
            }
        }

    fun velocityWithoutObserving(): Float = Snapshot.withoutReadObservation { velocity }
}

@Stable
class ScrollFx(
    val state: ScrollFxState,
    val listState: LazyListState,
)

@Composable
fun rememberScrollFx(listState: LazyListState): ScrollFx? {
    val reduce = LocalReduceGlass.current
    val scope = rememberCoroutineScope()
    val fx = remember(listState) { ScrollFx(ScrollFxState(scope), listState) }
    return if (reduce) null else fx
}

fun Modifier.scrollFxConnection(fx: ScrollFx?): Modifier = if (fx == null) this else nestedScroll(fx.state.connection)

/**
 * Drop-in replacement for [LazyListScope.item] on a vertical list. An item that first appears
 * while the list is scrolling slides in from the edge the list is moving toward (from below when
 * scrolling down, from above when scrolling up) while fading and scaling up. Visible items also
 * stretch slightly with the scroll speed. Everything is read in the graphics layer, so scrolling
 * causes no recomposition. With a null [fx] the item is shown without any effect.
 */
fun LazyListScope.scrollFxItem(
    fx: ScrollFx?,
    key: Any? = null,
    contentType: Any? = null,
    content: @Composable LazyItemScope.() -> Unit,
) {
    item(key = key, contentType = contentType) {
        val itemScope = this
        ScrollFxItem(fx) { itemScope.content() }
    }
}

@Composable
private fun ScrollFxItem(
    fx: ScrollFx?,
    content: @Composable () -> Unit,
) {
    if (fx == null) {
        content()
        return
    }
    val enterOffsetPx = with(LocalDensity.current) { EnterOffsetDp.dp.toPx() }
    val entryVelocity = remember { fx.state.velocityWithoutObserving() }
    val enterFrom = if (entryVelocity >= 0f) 1f else -1f
    val progress = remember { Animatable(if (abs(entryVelocity) > MovingThresholdPx) 0f else 1f) }
    var top by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableFloatStateOf(0f) }
    val viewportHeight = remember { floatArrayOf(0f) }
    var placed by remember { mutableStateOf(false) }

    LaunchedEffect(placed) {
        if (placed && progress.value < 1f) {
            progress.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
        }
    }

    Box(
        modifier =
            Modifier
                .graphicsLayer {
                    val enter = progress.value
                    val velocity = fx.state.velocity
                    if (enter >= 1f && velocity == 0f) return@graphicsLayer
                    val vh = viewportHeight[0]
                    val spread = if (vh > 0f) ((top + height / 2f) / vh - 0.5f).coerceIn(-0.5f, 1f) else 0f
                    translationY = enterFrom * (1f - enter) * enterOffsetPx + velocity * spread * SpreadFactor
                    val scale = EnterScaleFrom + (1f - EnterScaleFrom) * enter
                    scaleX = scale
                    scaleY = scale
                    alpha = EnterAlphaFrom + (1f - EnterAlphaFrom) * enter
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }.onGloballyPositioned { coordinates ->
                    top = coordinates.positionInParent().y
                    height = coordinates.size.height.toFloat()
                    viewportHeight[0] = fx.listState.layoutInfo.viewportSize.height
                        .toFloat()
                    if (!placed) placed = true
                },
        propagateMinConstraints = true,
    ) {
        content()
    }
}
