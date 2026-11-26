/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.state

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wavlin.innertube.YouTube
import com.wavlin.innertube.models.Album
import com.wavlin.innertube.models.Artist
import com.wavlin.innertube.models.SongItem
import com.wavlin.music.core.util.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

sealed interface SharedPlaylistState {
    data object Loading : SharedPlaylistState

    data class Loaded(
        val name: String,
        val songs: List<SongItem>,
    ) : SharedPlaylistState

    data object Failed : SharedPlaylistState
}

/**
 * Loads a playlist that someone shared (short code on the share server, or the older name + ids link)
 * for preview only. Nothing is written to the library until the user chooses to save it.
 */
@HiltViewModel
class SharedPlaylistViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val code: String = savedStateHandle["code"] ?: ""
        private val linkName: String = savedStateHandle["name"] ?: ""
        private val linkIds: String = savedStateHandle["ids"] ?: ""

        private val _state = MutableStateFlow<SharedPlaylistState>(SharedPlaylistState.Loading)
        val state: StateFlow<SharedPlaylistState> = _state.asStateFlow()

        init {
            load()
        }

        fun load() {
            _state.value = SharedPlaylistState.Loading
            viewModelScope.launch(Dispatchers.IO) {
                val (name, ids, tracks) = fetchSource()
                // Links made by newer builds carry each track's own metadata: show exactly those versions.
                if (!tracks.isNullOrEmpty()) {
                    _state.value = SharedPlaylistState.Loaded(name, tracks)
                    return@launch
                }
                val videoIds = ids.filter { it.isNotBlank() }.distinct()
                if (videoIds.isEmpty()) {
                    _state.value = SharedPlaylistState.Failed
                    return@launch
                }
                // The queue endpoint only takes a limited number of ids per request, so fetch in batches, in order.
                val batches = videoIds.chunked(BATCH_SIZE).map { YouTube.queue(it) }
                val failure = batches.firstOrNull { it.isFailure }?.exceptionOrNull()
                if (failure != null) {
                    reportException(failure)
                    _state.value = SharedPlaylistState.Failed
                    return@launch
                }
                val songs = batches.flatMap { it.getOrThrow() }
                _state.value =
                    if (songs.isEmpty()) SharedPlaylistState.Failed else SharedPlaylistState.Loaded(name, songs)
            }
        }

        private data class Source(
            val name: String,
            val ids: List<String>,
            val tracks: List<SongItem>? = null,
        )

        private fun parseTracks(array: org.json.JSONArray?): List<SongItem>? {
            if (array == null) return null
            val songs =
                List(array.length()) { index ->
                    val item = array.optJSONObject(index) ?: return@List null
                    val id = item.optString("id")
                    val title = item.optString("title")
                    if (id.isBlank() || title.isBlank()) return@List null
                    val artistArray = item.optJSONArray("artists")
                    val artists =
                        List(artistArray?.length() ?: 0) { i ->
                            val artist = artistArray?.optJSONObject(i) ?: return@List null
                            val artistName = artist.optString("name")
                            if (artistName.isBlank()) null else Artist(artistName, artist.optString("id").ifBlank { null })
                        }.filterNotNull()
                    val albumName = item.optString("album")
                    val albumId = item.optString("albumId")
                    SongItem(
                        id = id,
                        title = title,
                        artists = artists,
                        album = if (albumName.isNotBlank() && albumId.isNotBlank()) Album(albumName, albumId) else null,
                        duration = item.optInt("duration", -1).takeIf { it >= 0 },
                        thumbnail = item.optString("thumbnail").ifBlank { "https://i.ytimg.com/vi/$id/hqdefault.jpg" },
                        explicit = item.optBoolean("explicit", false),
                    )
                }.filterNotNull()
            return songs.ifEmpty { null }
        }

        private fun fetchSource(): Source {
            if (code.isEmpty()) return Source(linkName, linkIds.split(","))
            if (!code.matches(CODE_REGEX)) return Source("", emptyList())
            return runCatching {
                val connection = URL("https://$SHARE_PAGE_HOST/p/$code.json").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000
                    val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    val idArray = json.getJSONArray("ids")
                    Source(
                        json.optString("name"),
                        List(idArray.length()) { idArray.getString(it) },
                        parseTracks(json.optJSONArray("tracks")),
                    )
                } finally {
                    connection.disconnect()
                }
            }.onFailure { reportException(it) }
                .getOrDefault(Source("", emptyList()))
        }

        private companion object {
            const val BATCH_SIZE = 50
            const val SHARE_PAGE_HOST = "wavlinsite.dvaustria741.workers.dev"
            val CODE_REGEX = Regex("[A-Za-z0-9]{4,32}")
        }
    }
