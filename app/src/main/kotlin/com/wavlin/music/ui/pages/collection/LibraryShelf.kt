/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.pages.collection

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wavlin.music.core.config.LibraryFilter
import com.wavlin.music.ui.kit.pageGlass
import com.wavlin.music.ui.kit.pageGlassAvailable
import com.wavlin.music.ui.style.WavlinSpace

private const val HeroHeightDp = 164

/** The bento layout needs Playlists, Songs and Albums; without all three the plain grid is used. */
fun hasBentoLayout(categories: List<LibraryCategory>): Boolean =
    listOf(LibraryFilter.PLAYLISTS, LibraryFilter.SONGS, LibraryFilter.ALBUMS)
        .all { filter -> categories.any { it.filter == filter } }

/**
 * Library root of the Wavlin layout: Playlists as one tall hero tile, Songs and Albums stacked beside it,
 * and everything else as a row of chips underneath.
 */
@Composable
fun LibraryBento(
    categories: List<LibraryCategory>,
    onSelect: (LibraryFilter) -> Unit,
    appleMusic: Boolean,
    modifier: Modifier = Modifier,
) {
    val hero = categories.first { it.filter == LibraryFilter.PLAYLISTS }
    val songs = categories.first { it.filter == LibraryFilter.SONGS }
    val albums = categories.first { it.filter == LibraryFilter.ALBUMS }
    val rest = categories.filter { it !== hero && it !== songs && it !== albums }
    val colors = MaterialTheme.colorScheme

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = WavlinSpace.Gutter).height(HeroHeightDp.dp),
        ) {
            BentoTile(
                category = hero,
                large = true,
                container = colors.primaryContainer,
                content = colors.onPrimaryContainer,
                appleMusic = appleMusic,
                onClick = { onSelect(hero.filter) },
                modifier = Modifier.weight(1.1f).fillMaxHeight(),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                BentoTile(
                    category = songs,
                    large = false,
                    container = colors.secondaryContainer,
                    content = colors.onSecondaryContainer,
                    appleMusic = appleMusic,
                    onClick = { onSelect(songs.filter) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                BentoTile(
                    category = albums,
                    large = false,
                    container = colors.tertiaryContainer,
                    content = colors.onTertiaryContainer,
                    appleMusic = appleMusic,
                    onClick = { onSelect(albums.filter) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
        if (rest.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = WavlinSpace.Gutter),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(rest, key = { it.filter.name }) { category ->
                    BentoTile(
                        category = category,
                        large = false,
                        container = colors.surfaceContainerHigh,
                        content = colors.onSurface,
                        appleMusic = appleMusic,
                        onClick = { onSelect(category.filter) },
                        modifier = Modifier.height(56.dp),
                        chip = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun BentoTile(
    category: LibraryCategory,
    large: Boolean,
    container: Color,
    content: Color,
    appleMusic: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    chip: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "bentoScale",
    )
    val shape = RoundedCornerShape(if (chip) 28.dp else if (appleMusic) 22.dp else 28.dp)
    val glass = appleMusic && pageGlassAvailable()

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (glass) {
                    Modifier.pageGlass(shape, 0.1f).clip(shape).background(container.copy(alpha = 0.34f))
                } else {
                    Modifier.clip(shape).background(container)
                },
            )
            .then(
                if (appleMusic && !glass) {
                    Modifier.border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant), shape)
                } else {
                    Modifier
                },
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = if (chip) Alignment.CenterStart else Alignment.TopStart,
    ) {
        when {
            large -> {
                Icon(
                    painter = painterResource(category.iconRes),
                    contentDescription = null,
                    tint = content.copy(alpha = 0.14f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 22.dp, y = 22.dp)
                        .size(120.dp)
                        .rotate(-12f),
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(16.dp)
                        .size(40.dp)
                        .background(content.copy(alpha = 0.12f), CircleShape)
                        .align(Alignment.TopStart),
                ) {
                    Icon(
                        painter = painterResource(category.iconRes),
                        contentDescription = null,
                        tint = content,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    text = category.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
            }
            else -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .then(if (chip) Modifier else Modifier.fillMaxSize())
                        .padding(horizontal = if (chip) 18.dp else 16.dp),
                ) {
                    Icon(
                        painter = painterResource(category.iconRes),
                        contentDescription = null,
                        tint = if (chip) MaterialTheme.colorScheme.primary else content,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = category.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Collection card styled as a record sleeve: the disc peeks out of the right edge and gives a small spin when pressed. */
@Composable
fun LibrarySleeveCard(
    collection: LibraryCollection,
    appleMusic: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (collection.colorSlot % 3) {
        0 -> colors.primaryContainer to colors.onPrimaryContainer
        1 -> colors.secondaryContainer to colors.onSecondaryContainer
        else -> colors.tertiaryContainer to colors.onTertiaryContainer
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "sleeveScale",
    )
    val spin by animateFloatAsState(
        targetValue = if (pressed) 36f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 260f),
        label = "sleeveSpin",
    )
    val shape = RoundedCornerShape(if (appleMusic) 22.dp else 28.dp)
    val glass = appleMusic && pageGlassAvailable()

    Box(
        modifier = modifier
            .width(176.dp)
            .height(112.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (glass) {
                    Modifier.pageGlass(shape, 0.1f).clip(shape).background(container.copy(alpha = 0.34f))
                } else {
                    Modifier.clip(shape).background(container)
                },
            )
            .then(
                if (appleMusic && !glass) {
                    Modifier.border(BorderStroke(0.5.dp, colors.outlineVariant), shape)
                } else {
                    Modifier
                },
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = 44.dp)
                .size(112.dp)
                .rotate(spin)
                .drawBehind {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = size.minDimension / 2f
                    drawCircle(color = content.copy(alpha = 0.1f), radius = radius, center = center)
                    listOf(0.82f, 0.64f).forEach { ring ->
                        drawCircle(
                            color = content.copy(alpha = 0.14f),
                            radius = radius * ring,
                            center = center,
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
                    drawCircle(color = container, radius = radius * 0.3f, center = center)
                },
        ) {
            Icon(
                painter = painterResource(collection.iconRes),
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = collection.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .widthIn(max = 100.dp)
                .padding(start = 16.dp, bottom = 14.dp),
        )
    }
}
