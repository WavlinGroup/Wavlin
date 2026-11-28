/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.pages.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import coil3.compose.AsyncImage
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.wavlin.innertube.models.AlbumItem
import com.wavlin.innertube.models.EpisodeItem
import com.wavlin.innertube.models.PlaylistItem
import com.wavlin.innertube.models.PodcastItem
import com.wavlin.innertube.models.SongItem
import com.wavlin.innertube.models.YTItem
import com.wavlin.music.R
import com.wavlin.music.db.entities.Album
import com.wavlin.music.db.entities.LocalItem
import com.wavlin.music.db.entities.Song
import com.wavlin.music.ui.kit.LocalInterfaceAppleMusic
import com.wavlin.music.ui.kit.appleGlass
import com.wavlin.music.ui.kit.appleGlassSupported
import com.wavlin.music.ui.style.WavlinSpace
import com.wavlin.music.ui.support.resize
import kotlin.math.absoluteValue

private val StageShape = RoundedCornerShape(30.dp)
private val PosterShape = RoundedCornerShape(22.dp)

private val CoverScrim =
    Brush.verticalGradient(
        0.45f to Color.Transparent,
        1f to Color.Black.copy(alpha = 0.82f),
    )

fun YTItem.stageSubtitle(): String? =
    when (this) {
        is SongItem -> artists.joinToString { it.name }
        is AlbumItem -> artists?.joinToString { it.name }
        is PlaylistItem -> author?.name
        is PodcastItem -> author?.name
        is EpisodeItem -> author?.name
        else -> null
    }?.takeIf { it.isNotBlank() }

/** Home hero: one large cover per page with the neighbour peeking in. [lastPage] closes the row. */
@Composable
fun HomeStage(
    items: List<YTItem>,
    isActive: (YTItem) -> Boolean,
    isPlaying: Boolean,
    onClick: (YTItem) -> Unit,
    onLongClick: (YTItem) -> Unit,
    lastPage: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    StagePager(
        items = items,
        title = { it.title },
        subtitle = { it.stageSubtitle() },
        thumbnailUrl = { it.thumbnail },
        isActive = isActive,
        isPlaying = isPlaying,
        onClick = onClick,
        onLongClick = onLongClick,
        lastPage = lastPage,
        modifier = modifier,
    )
}

/** The same large-cover row for the user's own library items. */
@Composable
fun HomeLocalStage(
    items: List<LocalItem>,
    isActive: (LocalItem) -> Boolean,
    isPlaying: Boolean,
    onClick: (LocalItem) -> Unit,
    onLongClick: (LocalItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    StagePager(
        items = items,
        title = { it.title },
        subtitle = {
            when (it) {
                is Song -> it.artists.joinToString { artist -> artist.name }
                is Album -> it.artists.joinToString { artist -> artist.name }
                else -> null
            }?.takeIf { text -> text.isNotBlank() }
        },
        thumbnailUrl = { it.thumbnailUrl },
        isActive = isActive,
        isPlaying = isPlaying,
        onClick = onClick,
        onLongClick = onLongClick,
        lastPage = null,
        modifier = modifier,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun <T> StagePager(
    items: List<T>,
    title: (T) -> String,
    subtitle: (T) -> String?,
    thumbnailUrl: (T) -> String?,
    isActive: (T) -> Boolean,
    isPlaying: Boolean,
    onClick: (T) -> Unit,
    onLongClick: (T) -> Unit,
    lastPage: (@Composable (Modifier) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val cardWidth = min(maxWidth - 64.dp, 380.dp)
        val cardHeight = cardWidth * 1.08f
        val pagerState = rememberPagerState(pageCount = { items.size + if (lastPage != null) 1 else 0 })

        HorizontalPager(
            state = pagerState,
            pageSize = PageSize.Fixed(cardWidth),
            pageSpacing = WavlinSpace.M,
            contentPadding = PaddingValues(horizontal = WavlinSpace.Gutter),
            modifier = Modifier.fillMaxWidth().height(cardHeight),
        ) { page ->
            val settle =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val distance =
                            ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                                .absoluteValue
                                .coerceIn(0f, 1f)
                        scaleY = 1f - 0.08f * distance
                        scaleX = 1f - 0.04f * distance
                    }.clip(StageShape)

            val item = items.getOrNull(page)
            if (item == null) {
                lastPage?.invoke(settle)
            } else {
                StageCard(
                    title = title(item),
                    subtitle = subtitle(item),
                    thumbnailUrl = thumbnailUrl(item),
                    active = isActive(item),
                    isPlaying = isPlaying,
                    modifier =
                        settle.combinedClickable(
                            onClick = { onClick(item) },
                            onLongClick = { onLongClick(item) },
                        ),
                )
            }
        }
    }
}

@Composable
private fun StageCard(
    title: String,
    subtitle: String?,
    thumbnailUrl: String?,
    active: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    // Apple Music style: the play button is liquid glass that refracts this card's own cover. The glass must be a
    // sibling of the layer it samples, so the cover and scrim are recorded into [coverBackdrop] and the button sits beside them.
    val glassPlay = LocalInterfaceAppleMusic.current && appleGlassSupported()
    val coverBackdrop = rememberLayerBackdrop { drawContent() }

    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Box(modifier = Modifier.fillMaxSize().then(if (glassPlay) Modifier.layerBackdrop(coverBackdrop) else Modifier)) {
            AsyncImage(
                model = thumbnailUrl?.resize(720, 720),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(modifier = Modifier.fillMaxSize().background(CoverScrim))
        }

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(WavlinSpace.M),
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(WavlinSpace.Gutter),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val playIcon = if (active && isPlaying) R.drawable.pause else R.drawable.play
            if (glassPlay) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .size(56.dp)
                            .appleGlass(CircleShape, tintAlpha = 0.1f, backdrop = coverBackdrop)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.26f), CircleShape),
                ) {
                    Icon(
                        painter = painterResource(playIcon),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                ) {
                    Icon(
                        painter = painterResource(playIcon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

/** Tall cover card with the title laid over the artwork. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomePoster(
    title: String,
    thumbnailUrl: String?,
    active: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .width(148.dp)
                .height(204.dp)
                .clip(PosterShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(
                    if (active) {
                        Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), PosterShape)
                    } else {
                        Modifier
                    },
                ).combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AsyncImage(
            model = thumbnailUrl?.resize(544, 544),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(modifier = Modifier.fillMaxSize().background(CoverScrim))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(WavlinSpace.M),
        )
    }
}

/** Shuffle and song recognition as part of the page, so nothing floats over the content. */
@Composable
fun HomeActions(
    onShuffle: () -> Unit,
    onRecognize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(WavlinSpace.M),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = WavlinSpace.Gutter, vertical = WavlinSpace.S),
    ) {
        HomeAction(
            icon = R.drawable.shuffle,
            label = stringResource(R.string.shuffle),
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
            onClick = onShuffle,
            modifier = Modifier.weight(1f),
        )
        HomeAction(
            icon = R.drawable.mic,
            label = stringResource(R.string.recognize_music),
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            content = MaterialTheme.colorScheme.onSurface,
            onClick = onRecognize,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HomeAction(
    icon: Int,
    label: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = container,
        contentColor = content,
        modifier = modifier.height(52.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = WavlinSpace.M),
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(WavlinSpace.S))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
