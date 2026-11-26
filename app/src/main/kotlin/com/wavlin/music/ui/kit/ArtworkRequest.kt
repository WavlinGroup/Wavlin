package com.wavlin.music.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.request.ImageRequest

/**
 * Artwork request that is built once per [data] instead of on every recomposition of the list item.
 * Memory, disk and network caching are Coil's defaults, so the request behaves as before.
 */
@Composable
fun rememberArtworkRequest(data: Any?): ImageRequest {
    val context = LocalContext.current
    return remember(context, data) { ImageRequest.Builder(context).data(data).build() }
}
