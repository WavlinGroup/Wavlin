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
import java.math.BigInteger

/**
 * Downloads a Spotify track as a local audio file and adds it to the library with Spotify's own
 * title, artist and album art. Because the result is a local file it plays offline too.
 *
 * Provider chain (tried in order, stops at the first one that returns audio bytes):
 *   1. Spotify spclient CDN  — uses the authenticated web-player token (sp_dc session) to fetch
 *                              the exact track from Spotify's own storage CDN. Requires the user
 *                              to be signed in; falls through when no session is available.
 *   2. api.spotifydown.com   — third-party proxy; also pulls from Spotify's CDN for most tracks.
 *   3. spotifydown.com       — legacy site API; last-resort, may source from YouTube.
 *   4. Spotify 30s preview   — DRM-free preview from p.scdn.co; exact audio but capped at 30 s.
 */
object SpotifyDirectDownloader {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    private val TRACK_ID_REGEX = Regex("spotify\\.com/track/([A-Za-z0-9]+)")
    private val UNSAFE_FILE_CHARS = Regex("[/\\\\:*?\"<>|]")
    private val json = Json { ignoreUnknownKeys = true }

    // ── Base-62 → hex GID conversion (Spotify track ID → 32-char hex GID) ───
    // Spotify internally identifies tracks by a 128-bit GID encoded as 22-char base62.
    // The spclient metadata endpoint uses the hex form of that GID.
    private val BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private fun trackIdToHexGid(trackId: String): String? = runCatching {
        var n = BigInteger.ZERO
        for (c in trackId) {
            val digit = BASE62.indexOf(c)
            if (digit < 0) return@runCatching null
            n = n.multiply(BigInteger.valueOf(62)).add(BigInteger.valueOf(digit.toLong()))
        }
        n.toString(16).padStart(32, '0')
    }.getOrNull()

    /**
     * Returns the new local song, or null when the track can't be fetched.
     * Pass [spotifyAccessToken] from [SpotifySession.validAccessToken] to enable the full-track
     * CDN path; without it the downloader falls back to third-party providers.
     */
    suspend fun downloadAsLocalSong(
        context: Context,
        database: MusicDatabase,
        track: SpotifyTrack,
        spotifyAccessToken: String? = null,
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

    /**
     * Resolves a direct audio URL for [trackId] using the provider chain, then downloads and returns
     * the raw bytes. Returns null when every provider fails. Exposed for callers that need raw bytes
     * rather than a persisted local song.
     */
    suspend fun fetchAudio(trackId: String, spotifyAccessToken: String? = null): ByteArray? {
        val client = HttpClient(CIO) {
            install(HttpTimeout) { requestTimeoutMillis = 90_000; connectTimeoutMillis = 15_000 }
            expectSuccess = false
        }
        try {
            val audioUrl = resolveAudioUrl(client, trackId, spotifyAccessToken) ?: return null
            return client
                .get(audioUrl) { header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile)") }
                .readBytes()
                .takeIf { it.isNotEmpty() }
        } finally {
            client.close()
        }
    }

    /**
     * Returns a direct CDN audio URL for [trackId] by trying each provider in order.
     * Returns null when every provider fails or is rate-limited.
     */
    suspend fun resolveAudioUrl(client: HttpClient, trackId: String, spotifyAccessToken: String? = null): String? =
        (if (!spotifyAccessToken.isNullOrBlank()) trySpotifyClientCdn(client, trackId, spotifyAccessToken) else null)
            ?: tryApiSpotifydown(client, trackId)
            ?: tryLegacySpotifydown(client, trackId)
            ?: trySpotifyPreview(client, trackId)

    // ── Provider 1: Spotify spclient (authenticated, full track) ─────────────
    // spclient.wg.spotify.com/metadata/4/track/{hexGid} returns PROTOBUF binary, not JSON.
    // We parse just the fields we need with a minimal hand-rolled protobuf reader:
    //   Track.file[]  → field 3, repeated message
    //     AudioFile.file_id → field 1, bytes (16 bytes → 32-char hex fileId)
    //     AudioFile.format  → field 2, varint enum
    //       OGG_VORBIS_96=5, OGG_VORBIS_160=6, OGG_VORBIS_320=7, MP3_320=13, MP3_256=15
    // Then: storage-resolve/{fileId} returns JSON with cdnurl[].
    private suspend fun trySpotifyClientCdn(client: HttpClient, trackId: String, accessToken: String): String? =
        runCatching {
            val hexGid = trackIdToHexGid(trackId) ?: return@runCatching null

            // Step 1 — fetch protobuf metadata
            val metaBytes = client.get("https://spclient.wg.spotify.com/metadata/4/track/$hexGid") {
                header("Authorization", "Bearer $accessToken")
                header("User-Agent", USER_AGENT)
                header("app-platform", "WebPlayer")
                header("Origin", "https://open.spotify.com")
                header("Referer", "https://open.spotify.com/")
                // no Accept: application/json — we want the real protobuf
            }.readBytes()
            if (metaBytes.isEmpty()) return@runCatching null

            // Step 2 — parse protobuf to get the best file ID
            val fileId = bestFileIdFromProto(metaBytes) ?: return@runCatching null

            // Step 3 — resolve CDN URL
            // Try gew1 first (EU), fall back to apresolve (US)
            val cdnUrl = resolveStorageUrl(client, fileId, accessToken, "gew1")
                ?: resolveStorageUrl(client, fileId, accessToken, "gue1")
                ?: return@runCatching null

            cdnUrl
        }.getOrNull()

    /** Minimal protobuf varint + length-delimited reader that extracts Track.file[] entries. */
    private fun bestFileIdFromProto(bytes: ByteArray): String? {
        // Format priority: OGG_VORBIS_320=7 > OGG_VORBIS_160=6 > MP3_320=13 > MP3_256=15 > OGG_VORBIS_96=5 > anything
        val formatPriority = mapOf(7 to 0, 6 to 1, 13 to 2, 15 to 3, 5 to 4)
        data class AudioFile(val fileIdHex: String, val format: Int)

        val files = mutableListOf<AudioFile>()
        var pos = 0

        fun readVarint(): Long {
            var result = 0L; var shift = 0
            while (pos < bytes.size) {
                val b = bytes[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return result
        }

        fun skipField(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> { pos += 8 }
                2 -> { val len = readVarint().toInt(); pos += len }
                5 -> { pos += 4 }
                else -> pos = bytes.size // bail on unknown wire type
            }
        }

        // Parse top-level Track message; field 3 = file (repeated AudioFile message)
        while (pos < bytes.size) {
            val tag = readVarint()
            val fieldNum = (tag shr 3).toInt()
            val wireType = (tag and 0x7L).toInt()
            if (wireType == 2 && fieldNum == 3) {
                // Length-delimited AudioFile submessage
                val msgLen = readVarint().toInt()
                val end = pos + msgLen
                var fileIdBytes: ByteArray? = null
                var format = -1
                while (pos < end) {
                    val innerTag = readVarint()
                    val innerField = (innerTag shr 3).toInt()
                    val innerWire = (innerTag and 0x7L).toInt()
                    when {
                        innerField == 1 && innerWire == 2 -> {
                            // file_id: bytes (16 bytes)
                            val len = readVarint().toInt()
                            fileIdBytes = bytes.copyOfRange(pos, pos + len)
                            pos += len
                        }
                        innerField == 2 && innerWire == 0 -> {
                            // format: varint enum
                            format = readVarint().toInt()
                        }
                        else -> skipField(innerWire)
                    }
                }
                pos = end
                if (fileIdBytes != null && fileIdBytes.size == 16) {
                    val hex = fileIdBytes.joinToString("") { "%02x".format(it) }
                    files += AudioFile(hex, format)
                }
            } else {
                skipField(wireType)
            }
        }

        if (files.isEmpty()) return null
        return files.minByOrNull { formatPriority[it.format] ?: 99 }?.fileIdHex
    }

    private suspend fun resolveStorageUrl(client: HttpClient, fileId: String, accessToken: String, region: String): String? =
        runCatching {
            val resp = client.get(
                "https://$region-spclient.spotify.com/storage-resolve/files/audio/interactive_prefetch/$fileId" +
                    "?version=10000000&product=9&platform=39&alt=json"
            ) {
                header("Authorization", "Bearer $accessToken")
                header("Accept", "application/json")
                header("User-Agent", USER_AGENT)
                header("app-platform", "WebPlayer")
                header("Origin", "https://open.spotify.com")
                header("Referer", "https://open.spotify.com/")
            }
            if (resp.status.value !in 200..299) return@runCatching null
            val root = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            root["cdnurl"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
        }.getOrNull()

    // ── Provider 2: api.spotifydown.com ──────────────────────────────────────
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

    // ── Provider 3: spotifydown.com (legacy, YouTube-sourced) ────────────────
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

    // ── Provider 4: Spotify 30-second preview (p.scdn.co, DRM-free) ─────────
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
