/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.integration.spotify

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.wavlin.music.db.MusicDatabase
import com.wavlin.music.data.local.LocalSongs
import com.wavlin.music.models.MediaMetadata
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Downloads a Spotify track as a local audio file and adds it to the library with Spotify's own
 * title, artist and album art. Because the result is a local file it plays offline too.
 *
 * The spclient CDN path delivers a Widevine-encrypted OGG stream that ExoPlayer cannot play
 * without the decryption key — so we skip it entirely. Provider chain:
 *   1. api.spotifydown.com   — third-party proxy; returns a plain MP3 from Spotify's CDN.
 *   2. spotifydown.com       — legacy site API; last-resort, also returns MP3.
 *   3. Spotify 30s preview   — DRM-free preview from p.scdn.co; capped at 30 s.
 *
 * Callers should try [SpotifyImporter] / [SpotifyMatcher] first; that path streams full-quality
 * audio via YouTube Music and doesn't need a download at all.
 */
object SpotifyDirectDownloader {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    private val TRACK_ID_REGEX = Regex("spotify\\.com/track/([A-Za-z0-9]+)")
    private val UNSAFE_FILE_CHARS = Regex("[/\\\\:*?\"<>|]")
    private val json = Json { ignoreUnknownKeys = true }

    /** Returns the new local song, or null when every provider fails. */
    suspend fun downloadAsLocalSong(
        context: Context,
        database: MusicDatabase,
        track: SpotifyTrack,
        @Suppress("UNUSED_PARAMETER") spotifyAccessToken: String? = null,
    ): MediaMetadata? =
        withContext(Dispatchers.IO) {
            val trackId = track.url?.let { TRACK_ID_REGEX.find(it)?.groupValues?.getOrNull(1) } ?: return@withContext null
            val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
            val safeName = "${track.artist} - ${track.title}".replace(UNSAFE_FILE_CHARS, "_")

            val client = HttpClient(CIO) {
                install(HttpTimeout) { requestTimeoutMillis = 90_000; connectTimeoutMillis = 15_000 }
                expectSuccess = false
            }
            try {
                val audioUrl = resolveAudioUrl(client, trackId, spotifyAccessToken) ?: return@withContext null
                // Pick extension from URL so ExoPlayer selects the right demuxer
                val ext = when {
                    audioUrl.contains(".mp3", ignoreCase = true) || audioUrl.contains("mp3-preview") -> "mp3"
                    audioUrl.contains(".ogg", ignoreCase = true) -> "ogg"
                    audioUrl.contains(".m4a", ignoreCase = true) -> "m4a"
                    else -> "opus"
                }
                val audioFile = File(musicDir, "$safeName.$ext")
                if (!audioFile.exists() || audioFile.length() == 0L) {
                    val bytes = client.get(audioUrl) {
                        header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile)")
                    }.readBytes()
                    if (bytes.isEmpty()) return@withContext null
                    audioFile.writeBytes(bytes)
                }
                val song = LocalSongs.add(context, database, Uri.fromFile(audioFile), track.title, track.artist, track.coverUrl)
                com.wavlin.music.core.util.ArtworkStore.save(context, track.coverUrl)
                song
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } finally {
                client.close()
            }
        }

    /** Resolves and downloads raw audio bytes. Returns null when every provider fails. */
    suspend fun fetchAudio(trackId: String): ByteArray? {
        val client = HttpClient(CIO) {
            install(HttpTimeout) { requestTimeoutMillis = 90_000; connectTimeoutMillis = 15_000 }
            expectSuccess = false
        }
        try {
            val audioUrl = resolveAudioUrl(client, trackId) ?: return null
            return client
                .get(audioUrl) { header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile)") }
                .readBytes()
                .takeIf { it.isNotEmpty() }
        } finally {
            client.close()
        }
    }

    /** Returns a direct audio URL by trying each provider in order. */
    suspend fun resolveAudioUrl(client: HttpClient, trackId: String): String? =
        tryApiSpotifydown(client, trackId)
            ?: tryLegacySpotifydown(client, trackId)
            ?: trySpotifyPreview(client, trackId)

    // ── Provider 1: api.spotifydown.com ──────────────────────────────────────
    // Uses Spotify's own CDN; the returned link is the exact track.
    private suspend fun tryApiSpotifydown(client: HttpClient, trackId: String): String? =
        runCatching {
            val response =
                client
                    .get("https://api.spotifydown.com/download/$trackId") {
                        header("User-Agent", USER_AGENT)
                        header("Origin", "https://spotifydown.com")
                        header("Referer", "https://spotifydown.com/")
                        header("Accept", "application/json, text/plain, */*")
                        header("Accept-Language", "en-US,en;q=0.9")
                    }.bodyAsText()
            val root = json.parseToJsonElement(response).jsonObject
            val success = root["success"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            if (success == true) root["link"]?.jsonPrimitive?.contentOrNull else null
        }.getOrNull()

    // ── Provider 2: spotifydown.com (legacy) ────────────────────────────────
    // May return a "similar" YouTube upload rather than the exact track. Last resort only.
    private suspend fun tryLegacySpotifydown(client: HttpClient, trackId: String): String? =
        runCatching {
            // Prime the session so the API endpoint accepts the request
            runCatching {
                client.get("https://spotifydown.com/") {
                    header("User-Agent", USER_AGENT)
                    header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                }
            }
            val response =
                client
                    .get("https://spotifydown.com/api/download?trackId=$trackId") {
                        header("Origin", "https://spotifydown.com")
                        header("Referer", "https://spotifydown.com/")
                        header("User-Agent", USER_AGENT)
                        header("Accept", "application/json, text/plain, */*")
                        header("Accept-Language", "en-US,en;q=0.9")
                    }.bodyAsText()
            val root = json.parseToJsonElement(response).jsonObject
            val success = root["success"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            if (success == true) root["link"]?.jsonPrimitive?.contentOrNull else null
        }.getOrNull()

    // ── Provider 3: Spotify 30-second preview (p.scdn.co, DRM-free) ─────────
    // Spotify embeds a 30-second MP3 preview URL in the public embed page JSON for most tracks.
    // It is the actual Spotify CDN audio (not YouTube) so album art and metadata match exactly.
    // The clip is limited to 30 seconds; it's the best we can do without DRM decryption.
    private suspend fun trySpotifyPreview(client: HttpClient, trackId: String): String? =
        runCatching {
            val html = client.get("https://open.spotify.com/embed/track/$trackId") {
                header("User-Agent", USER_AGENT)
                header("Accept-Language", "en")
            }.bodyAsText()
            val nextData = Regex("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1) ?: return@runCatching null
            val root = json.parseToJsonElement(nextData)
            findPreviewUrl(root)
        }.getOrNull()

    /** Recursively searches a JSON tree for a Spotify preview URL (p.scdn.co/mp3-preview/…). */
    private fun findPreviewUrl(element: JsonElement): String? =
        when (element) {
            is JsonObject -> {
                // Direct key hits first
                val direct = listOf("audioPreview", "previewUrl", "preview_url").firstNotNullOfOrNull { key ->
                    (element[key] as? JsonObject)?.let {
                        it["url"]?.jsonPrimitive?.contentOrNull?.takeIf { u -> u.contains("scdn.co") }
                            ?: it["src"]?.jsonPrimitive?.contentOrNull?.takeIf { u -> u.contains("scdn.co") }
                    } ?: element[key]?.jsonPrimitive?.contentOrNull?.takeIf { u -> u.contains("scdn.co") }
                }
                direct ?: element.values.firstNotNullOfOrNull { findPreviewUrl(it) }
            }
            is JsonArray -> element.firstNotNullOfOrNull { findPreviewUrl(it) }
            else -> null
        }
}
