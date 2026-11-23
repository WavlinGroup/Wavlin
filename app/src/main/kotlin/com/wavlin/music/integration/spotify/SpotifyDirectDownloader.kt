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
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
 * Downloads a Spotify track as a local audio file and adds it to the library.
 *
 * Provider chain (tried in order, stops at first success):
 *   1. lucida.to         — multi-service proxy; serves full MP3/FLAC from Deezer/Tidal.
 *                          No token required. Best coverage including slowed/reverb edits.
 *   2. Deezer via ISRC   — ISRC lookup → Deezer ID → loaderapp proxy full MP3.
 *   3. api.spotifydown.com — third-party proxy (sources from YouTube).
 *   4. spotifydown.com   — legacy API fallback (sources from YouTube).
 *   5. SoundCloud search — full-length stream; covers indie/niche tracks.
 *   Returns null (download fails) if no full-length source is found.
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
        spotifyAccessToken: String? = null,
    ): MediaMetadata? =
        withContext(Dispatchers.IO) {
            val trackId = track.url
                ?.let { TRACK_ID_REGEX.find(it)?.groupValues?.getOrNull(1) }
                ?: return@withContext null
            val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
            val safeName = "${track.artist} - ${track.title}".replace(UNSAFE_FILE_CHARS, "_")

            val client = HttpClient(CIO) {
                install(HttpTimeout) { requestTimeoutMillis = 90_000; connectTimeoutMillis = 15_000 }
                expectSuccess = false
            }
            try {
                val audioUrl = resolveAudioUrl(client, trackId, track.title, track.artist, spotifyAccessToken)
                    ?: return@withContext null
                val ext = when {
                    audioUrl.contains(".mp3", ignoreCase = true)
                        || audioUrl.contains("mp3-preview")
                        || audioUrl.contains("sndcdn.com")
                        || audioUrl.contains("lucida.to") -> "mp3"
                    audioUrl.contains(".ogg", ignoreCase = true) -> "ogg"
                    audioUrl.contains(".m4a", ignoreCase = true) -> "m4a"
                    else -> "mp3"
                }
                val audioFile = File(musicDir, "$safeName.$ext")
                if (!audioFile.exists() || audioFile.length() == 0L) {
                    val bytes = client.get(audioUrl) {
                        header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile)")
                        header("Accept", "*/*")
                    }.readBytes()
                    if (bytes.isEmpty()) return@withContext null
                    audioFile.writeBytes(bytes)
                }
                val song = LocalSongs.add(
                    context, database, Uri.fromFile(audioFile),
                    track.title, track.artist, track.coverUrl,
                )
                com.wavlin.music.core.util.ArtworkStore.save(context, track.coverUrl)
                song
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
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
    suspend fun resolveAudioUrl(
        client: HttpClient,
        trackId: String,
        title: String = "",
        artist: String = "",
        spotifyAccessToken: String? = null,
    ): String? =
        tryLucida(client, trackId)
            ?: tryDeezerViaIsrc(client, trackId, spotifyAccessToken)
            ?: tryApiSpotifydown(client, trackId)
            ?: tryLegacySpotifydown(client, trackId)
            ?: (if (title.isNotBlank() || artist.isNotBlank()) tryAudioFromSoundCloud(client, title, artist) else null)

    // ── Provider 1: lucida.to (multi-service proxy, full track, no token) ───
    // POST lucida.to/api/load → poll server for "completed" → download binary URL.
    // Lucida routes Spotify URLs to Deezer/Tidal/SoundCloud internally, so it
    // covers slowed+reverb edits and niche tracks that YouTube proxies miss.
    private suspend fun tryLucida(client: HttpClient, trackId: String): String? = runCatching {
        val spotifyUrl = "https://open.spotify.com/track/$trackId"
        val payload = buildString {
            append("""{"account":{"id":"US","type":"country"},""")
            append(""""downscale":"mp3-320","handoff":true,"metadata":false,"private":true,""")
            append(""""token":{"expiry":1727419066,"primary":"XLj8xzbZJSfloOrw-XUmNGAmG6k"},""")
            append(""""upload":{"enabled":false,"service":"pixeldrain"},""")
            append(""""url":"$spotifyUrl"}""")
        }
        // Step 1 — submit job
        val initText = client.post("https://lucida.to/api/load?url=/api/fetch/stream/v2") {
            header("User-Agent", USER_AGENT)
            header("Origin", "https://lucida.to")
            header("Referer", "https://lucida.to/")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }.bodyAsText()
        val initRoot = json.parseToJsonElement(initText).jsonObject
        if (initRoot["success"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() != true) return@runCatching null
        val handoff = initRoot["handoff"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        val server = initRoot["server"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        // Step 2 — poll until "completed" (max 60 s)
        val pollBase = "https://$server.lucida.to/api/fetch/request/$handoff"
        var result: String? = null
        for (i in 0 until 60) {
            delay(1_000)
            val pollRoot = runCatching {
                json.parseToJsonElement(
                    client.get(pollBase) { header("User-Agent", USER_AGENT) }.bodyAsText()
                ).jsonObject
            }.getOrNull() ?: continue
            when (pollRoot["status"]?.jsonPrimitive?.contentOrNull) {
                "completed" -> { result = "$pollBase/download"; break }
                "error" -> break
            }
        }
        result
    }.getOrNull()

    // ── Provider 2: Deezer full track via ISRC ──────────────────────────────
    // ISRC sources (tried in order, stops at first hit):
    //   A1) api.spotifydown.com/metadata/{id}    — no token, returns isrc directly
    //   A2) open.spotify.com/track/{id}           — canonical page __NEXT_DATA__, no token
    //   A3) api.spotify.com/v1/tracks/{id}        — OAuth fallback when token supplied
    // Then:
    //   B) api.deezer.com/track/isrc:{isrc} → Deezer track id
    //   C) dz.loaderapp.info → full unencrypted MP3 (128 kbps)
    private suspend fun tryDeezerViaIsrc(
        client: HttpClient,
        trackId: String,
        accessToken: String? = null,
    ): String? = runCatching {
        // A1 — ISRC from spotifydown metadata (no token required)
        val isrc = findIsrcViaSpotifydown(client, trackId)
            // A2 — ISRC from canonical open.spotify.com page __NEXT_DATA__
            ?: findIsrcViaOpenPage(client, trackId)
            // A3 — ISRC from Web API OAuth fallback
            ?: accessToken?.takeIf { it.isNotBlank() }?.let { token ->
                runCatching {
                    val resp = client.get("https://api.spotify.com/v1/tracks/$trackId") {
                        header("Authorization", "Bearer $token")
                        header("User-Agent", USER_AGENT)
                    }
                    if (resp.status.value !in 200..299) return@runCatching null
                    val root = json.parseToJsonElement(resp.bodyAsText()).jsonObject
                    root["external_ids"]?.jsonObject?.get("isrc")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
            }
            ?: return@runCatching null

        // B — Deezer track ID via ISRC
        val deezerResp = client.get("https://api.deezer.com/track/isrc:$isrc") {
            header("User-Agent", USER_AGENT)
        }
        if (deezerResp.status.value !in 200..299) return@runCatching null
        val deezerJson = json.parseToJsonElement(deezerResp.bodyAsText()).jsonObject
        if (deezerJson["error"] != null) return@runCatching null
        val deezerTrackId = deezerJson["id"]?.jsonPrimitive?.contentOrNull
            ?: return@runCatching null

        // C — full MP3 via loaderapp (open proxy backed by Deezer)
        val loaderResp = client.get(
            "https://dz.loaderapp.info/deezer" +
                "?quality=MP3_128" +
                "&url=https://www.deezer.com/track/$deezerTrackId"
        ) {
            header("User-Agent", USER_AGENT)
            header("Origin", "https://loader.to")
            header("Referer", "https://loader.to/")
        }
        when (val status = loaderResp.status.value) {
            in 200..299 -> {
                val body = loaderResp.bodyAsText()
                runCatching {
                    json.parseToJsonElement(body).jsonObject["url"]
                        ?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") }
                }.getOrNull() ?: body.trim().takeIf { it.startsWith("http") }
            }
            in 300..399 -> loaderResp.headers["Location"]?.takeIf { it.startsWith("http") }
            else -> null
        }
    }.getOrNull()

    // ── Provider 2: api.spotifydown.com ─────────────────────────────────────
    private suspend fun tryApiSpotifydown(client: HttpClient, trackId: String): String? =
        runCatching {
            val body = client.get("https://api.spotifydown.com/download/$trackId") {
                header("User-Agent", USER_AGENT)
                header("Origin", "https://spotifydown.com")
                header("Referer", "https://spotifydown.com/")
                header("Accept", "application/json, text/plain, */*")
                header("Accept-Language", "en-US,en;q=0.9")
                header("sec-fetch-site", "same-site")
                header("sec-fetch-mode", "cors")
                header("sec-fetch-dest", "empty")
            }.bodyAsText()
            val root = json.parseToJsonElement(body).jsonObject
            val ok = root["success"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            if (ok == true) root["link"]?.jsonPrimitive?.contentOrNull else null
        }.getOrNull()

    // ── Provider 3: spotifydown.com legacy ──────────────────────────────────
    private suspend fun tryLegacySpotifydown(client: HttpClient, trackId: String): String? =
        runCatching {
            runCatching { client.get("https://spotifydown.com/") { header("User-Agent", USER_AGENT) } }
            val body = client.get("https://spotifydown.com/api/download?trackId=$trackId") {
                header("Origin", "https://spotifydown.com")
                header("Referer", "https://spotifydown.com/")
                header("User-Agent", USER_AGENT)
                header("Accept", "application/json, text/plain, */*")
            }.bodyAsText()
            val root = json.parseToJsonElement(body).jsonObject
            val ok = root["success"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            if (ok == true) root["link"]?.jsonPrimitive?.contentOrNull else null
        }.getOrNull()

    // ── Provider 4: SoundCloud search ───────────────────────────────────────
    suspend fun tryAudioFromSoundCloud(client: HttpClient, title: String, artist: String): String? =
        runCatching {
            val clientId = fetchSoundCloudClientId(client) ?: return@runCatching null
            val query = "${artist.trim()} ${title.trim()}"
            val searchUrl = "https://api-v2.soundcloud.com/search/tracks" +
                "?q=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=10&client_id=$clientId"
            val searchRoot = json.parseToJsonElement(
                client.get(searchUrl) {
                    header("User-Agent", USER_AGENT)
                    header("Accept", "application/json")
                }.bodyAsText()
            ).jsonObject
            val collection = searchRoot["collection"]?.jsonArray
                ?: return@runCatching null
            if (collection.isEmpty()) return@runCatching null
            val matchTitle = title.lowercase().trim()
            val matchArtist = artist.lowercase().split(",", "&", " feat", " ft").first().trim()
            val trackObj = collection.maxByOrNull { item ->
                val obj = item.jsonObject
                val t = obj["title"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: ""
                val u = obj["user"]?.jsonObject?.get("username")?.jsonPrimitive?.contentOrNull?.lowercase() ?: ""
                val titleMatch = t.contains(matchTitle) ||
                    matchTitle.contains(t.take(matchTitle.length.coerceAtLeast(1)))
                val artistMatch = u.contains(matchArtist) ||
                    matchArtist.contains(u.take(matchArtist.length.coerceAtLeast(1)))
                when { titleMatch && artistMatch -> 2; titleMatch -> 1; else -> 0 }
            }?.jsonObject ?: return@runCatching null
            val permalink = trackObj["permalink_url"]?.jsonPrimitive?.contentOrNull
                ?: return@runCatching null
            val trackApiUrl = "https://api-v2.soundcloud.com/resolve" +
                "?url=${java.net.URLEncoder.encode(permalink, "UTF-8")}&client_id=$clientId"
            val trackRoot = json.parseToJsonElement(
                client.get(trackApiUrl) { header("User-Agent", USER_AGENT) }.bodyAsText()
            ).jsonObject
            val streamsUrl = trackRoot["media"]?.jsonObject
                ?.get("transcodings")?.jsonArray
                ?.firstOrNull {
                    it.jsonObject["format"]?.jsonObject
                        ?.get("mime_type")?.jsonPrimitive?.contentOrNull == "audio/mpeg"
                }
                ?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                ?: return@runCatching null
            json.parseToJsonElement(
                client.get("$streamsUrl?client_id=$clientId") { header("User-Agent", USER_AGENT) }.bodyAsText()
            ).jsonObject["url"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()

    @Volatile private var soundCloudClientId: String? = null

    private suspend fun fetchSoundCloudClientId(client: HttpClient): String? {
        soundCloudClientId?.let { return it }
        return runCatching {
            val html = client.get("https://soundcloud.com") { header("User-Agent", USER_AGENT) }.bodyAsText()
            val scriptUrl = Regex("""<script[^>]+src="(https://a-v2\.sndcdn\.com/assets/[^"]+\.js)"""")
                .findAll(html).lastOrNull()?.groupValues?.get(1) ?: return@runCatching null
            val js = client.get(scriptUrl) { header("User-Agent", USER_AGENT) }.bodyAsText()
            val id = Regex("""client_id\s*[:=]\s*"([A-Za-z0-9]{32})"""").find(js)?.groupValues?.get(1)
                ?: return@runCatching null
            soundCloudClientId = id
            id
        }.getOrNull()
    }

    // ── A1: ISRC from api.spotifydown.com/metadata (no token required) ──────
    // Same origin we already hit for /download — returns {"isrc":"…"} directly.
    private suspend fun findIsrcViaSpotifydown(client: HttpClient, trackId: String): String? =
        runCatching {
            val body = client.get("https://api.spotifydown.com/metadata/$trackId") {
                header("User-Agent", USER_AGENT)
                header("Origin", "https://spotifydown.com")
                header("Referer", "https://spotifydown.com/")
                header("Accept", "application/json, text/plain, */*")
                header("Accept-Language", "en-US,en;q=0.9")
                header("sec-fetch-site", "same-site")
                header("sec-fetch-mode", "cors")
                header("sec-fetch-dest", "empty")
            }.bodyAsText()
            val root = json.parseToJsonElement(body).jsonObject
            root["isrc"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()

    // ── A2: ISRC from open.spotify.com/track/{id} __NEXT_DATA__ (no token) ─
    // The canonical open page (not embed) hydrates full track metadata
    // including external_ids.isrc in its __NEXT_DATA__ script block.
    private suspend fun findIsrcViaOpenPage(client: HttpClient, trackId: String): String? =
        runCatching {
            val html = client.get("https://open.spotify.com/track/$trackId") {
                header("User-Agent", USER_AGENT)
                header("Accept-Language", "en")
                header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            }.bodyAsText()
            val nextData = Regex(
                "<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>",
                RegexOption.DOT_MATCHES_ALL,
            ).find(html)?.groupValues?.get(1) ?: return@runCatching null
            findIsrcInElement(json.parseToJsonElement(nextData))
        }.getOrNull()

    private fun findIsrcInElement(element: JsonElement): String? =
        when (element) {
            is JsonObject -> {
                // Look for isrc directly or nested under external_ids
                element["isrc"]?.jsonPrimitive?.contentOrNull
                    ?: element["external_ids"]?.jsonObject?.get("isrc")?.jsonPrimitive?.contentOrNull
                    ?: element.values.firstNotNullOfOrNull { findIsrcInElement(it) }
            }
            is JsonArray -> element.firstNotNullOfOrNull { findIsrcInElement(it) }
            else -> null
        }

}
