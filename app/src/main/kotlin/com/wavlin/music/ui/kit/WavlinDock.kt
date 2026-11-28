/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.wavlin.music.ui.pages.nav.Screens
import com.wavlin.music.ui.style.WavlinMotion
import com.wavlin.music.ui.style.WavlinSpace
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlin.math.roundToInt

/** Scroll-driven state of the Wavlin dock. Put [connection] on the screen content, not on the player. */
@Stable
class DockScrollState(thresholdPx: Float) {
    private val tracker = DockScrollTracker(thresholdPx)

    var compact by mutableStateOf(false)
        private set

    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            compact = tracker.onScroll(consumed.y, available.y)
            return Offset.Zero
        }
    }

    fun expand() {
        tracker.expand()
        compact = false
    }
}

@Composable
fun rememberDockScrollState(): DockScrollState {
    val thresholdPx = with(LocalDensity.current) { 32.dp.toPx() }
    return remember(thresholdPx) { DockScrollState(thresholdPx) }
}

/**
 * Wavlin design bottom navigation: a floating glass capsule that closes into a small pill holding the
 * current tab while the content scrolls. Only the glass changes size. The tab row keeps its full width
 * and fades out before the pill label fades in, so text never collides mid-animation.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun WavlinDock(
    navigationItems: List<Screens>,
    currentRoute: String?,
    onItemClick: (Screens, Boolean) -> Unit,
    modifier: Modifier,
    pureBlack: Boolean,
    slimNav: Boolean,
    compact: Boolean,
    onExpand: () -> Unit,
    onSearchLongClick: (() -> Unit)?,
) {
    val foundIndex = navigationItems.indexOfFirst { isRouteSelected(currentRoute, it.route, navigationItems) }
    val glide = rememberTabGlide(foundIndex)
    val progress = animateFloatAsState(
        targetValue = if (compact) 1f else 0f,
        animationSpec = WavlinMotion.settleSpring(),
        label = "dockCompact",
    )
    val barHeight = if (slimNav) 52.dp else 60.dp
    val pillWidth = if (slimNav) 76.dp else 140.dp
    val pink = LocalPinkLilyTheme.current
    val colors = MaterialTheme.colorScheme

    BoxWithConstraints(
        modifier = modifier.padding(start = WavlinSpace.L, end = WavlinSpace.L, top = 6.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        val fullWidth = maxWidth
        val glass = if (pink) {
            Modifier.pinkGlass(CircleShape)
        } else {
            Modifier
                .legacyGlassPanel(
                    shape = CircleShape,
                    style = if (pureBlack) pureBlackHazeStyle() else HazeMaterials.thin(),
                    drawStroke = !LocalReduceGlass.current,
                )
                .background(
                    if (pureBlack) Color.Transparent else colors.surfaceContainerHigh.copy(alpha = 0.5f),
                    CircleShape,
                )
        }

        Box(
            modifier = Modifier
                .animatedDockWidth(progress, pillWidth.value / fullWidth.value)
                .height(barHeight)
                .then(glass)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            DockTabs(
                navigationItems = navigationItems,
                currentRoute = currentRoute,
                onItemClick = onItemClick,
                onSearchLongClick = onSearchLongClick,
                glide = glide,
                slimNav = slimNav,
                pureBlack = pureBlack,
                modifier = Modifier
                    .requiredWidth(fullWidth)
                    .fillMaxHeight()
                    .graphicsLayer { alpha = (1f - progress.value * 2.5f).coerceIn(0f, 1f) }
                    .then(if (compact) Modifier.clearAndSetSemantics {} else Modifier),
            )

            val current = navigationItems.getOrNull(glide.targetIndex)
            if (current != null) {
                Row(
                    modifier = Modifier
                        .requiredWidth(pillWidth)
                        .graphicsLayer { alpha = ((progress.value - 0.6f) * 2.5f).coerceIn(0f, 1f) }
                        .clearAndSetSemantics {},
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(current.iconIdActive),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = if (pureBlack) Color.White else colors.primary,
                    )
                    if (!slimNav) {
                        Spacer(Modifier.width(WavlinSpace.S))
                        Text(
                            text = stringResource(current.titleId),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (pureBlack) Color.White else colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Covers the faded tabs so a tap on the pill opens the dock instead of hitting a hidden tab.
                if (compact) {
                    val description = stringResource(current.titleId)
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable(onClickLabel = description, role = Role.Button, onClick = onExpand),
                    )
                }
            }
        }
    }
}

/** Reads the animation in the layout phase, so shrinking the dock does not recompose it every frame. */
internal fun Modifier.animatedDockWidth(progress: State<Float>, pillFraction: Float): Modifier =
    layout { measurable, constraints ->
        val full = constraints.maxWidth
        val pill = (full * pillFraction.coerceIn(0f, 1f)).roundToInt()
        val width = lerp(full, pill, progress.value.coerceIn(0f, 1f))
        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DockTabs(
    navigationItems: List<Screens>,
    currentRoute: String?,
    onItemClick: (Screens, Boolean) -> Unit,
    onSearchLongClick: (() -> Unit)?,
    glide: TabGlide,
    slimNav: Boolean,
    pureBlack: Boolean,
    modifier: Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val count = navigationItems.size.coerceAtLeast(1)
    val indicatorColor = when {
        LocalPinkLilyTheme.current -> PinkGlassTint.copy(alpha = 0.45f)
        pureBlack -> Color.White.copy(alpha = 0.16f)
        else -> colors.primaryContainer
    }

    Row(
        modifier = modifier
            .padding(6.dp)
            .drawBehind {
                val tabWidth = size.width / count
                drawRoundRect(
                    color = indicatorColor,
                    topLeft = Offset(glide.pillPos.value * tabWidth, 0f),
                    size = Size(tabWidth, size.height),
                    cornerRadius = CornerRadius(size.height / 2f),
                )
            },
    ) {
        navigationItems.forEachIndexed { index, screen ->
            val routeSelected = isRouteSelected(currentRoute, screen.route, navigationItems)
            val visuallySelected = index == glide.targetIndex
            val longClick = if (screen == Screens.Search) onSearchLongClick else null
            val tint by animateColorAsState(
                targetValue = when {
                    pureBlack -> Color.White.copy(alpha = if (visuallySelected) 1f else 0.7f)
                    visuallySelected -> colors.onPrimaryContainer
                    else -> colors.onSurfaceVariant
                },
                animationSpec = WavlinMotion.contentSwap(),
                label = "dockTint",
            )
            val iconScale = animateFloatAsState(
                targetValue = if (visuallySelected) 1.1f else 1f,
                animationSpec = WavlinMotion.pressSpring(),
                label = "dockIconScale",
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(CircleShape)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            if (routeSelected) {
                                onItemClick(screen, true)
                            } else {
                                glide.tap(index) { onItemClick(screen, false) }
                            }
                        },
                        onLongClick = longClick?.let { callback ->
                            {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                callback()
                            }
                        },
                    )
                    .semantics {
                        role = Role.Tab
                        selected = visuallySelected
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painter = painterResource(if (visuallySelected) screen.iconIdActive else screen.iconIdInactive),
                    contentDescription = stringResource(screen.titleId),
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer {
                            scaleX = iconScale.value
                            scaleY = iconScale.value
                        },
                    tint = tint,
                )
                if (!slimNav) {
                    Text(
                        text = stringResource(screen.titleId),
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
