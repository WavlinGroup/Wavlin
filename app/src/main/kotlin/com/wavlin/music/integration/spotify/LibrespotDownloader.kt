/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.integration.spotify

import android.content.Context
import com.google.protobuf.ByteString
import com.spotify.Authentication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import xyz.gianlu.librespot.audio.PlayableContentFeeder
import xyz.gianlu.librespot.audio.format.SuperAudioFormat
import xyz.gianlu.librespot.core.Session
import xyz.gianlu.librespot.metadata.TrackId
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Downloads a full Spotify track OGG stream using the librespot AP protocol.
 *
 * Authentication uses an existing Spotify access token (from SpotifyAuth / sp_dc flow),
 * so no separate Premium password is required. The token must have streaming scope
 * (the web player token does). Requires Spotify Premium — free accounts will get
 * CONTENT_RESTRICTED from the CDN.
 *
 * Returns the raw OGG/Vorbis bytes, or null on any failure.
 */
object LibrespotDownloader {

    private const val TAG = "LibrespotDownloader"

    /**
     * @param trackId   The 22-char base62 Spotify track ID (e.g. "0VWfkieYW5d9IPKKOEs7HM")
     * @param accessToken  A valid Spotify access token (from SpotifyAuth)
     */
    suspend fun downloadOgg(
        trackId: String,
        accessToken: String,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) {
            Timber.tag(TAG).w("No access token; skipping librespot")
            return@withContext null
        }

        var session: Session? = null
        try {
            // Build LoginCredentials using the access token
            val credentials = Authentication.LoginCredentials.newBuilder()
                .setUsername("")
                .setTyp(Authentication.AuthenticationType.AUTHENTICATION_SPOTIFY_TOKEN)
                .setAuthData(ByteString.copyFromUtf8(accessToken))
                .build()

            val conf = Session.Configuration.Builder()
                .setStoreCredentials(false)
                .build()

            session = Session.Builder(conf)
                .credentials(credentials)
                .create()

            val id = TrackId.fromBase62(trackId)
            val feeder = PlayableContentFeeder(session)
            val loaded = feeder.load(
                id,
                xyz.gianlu.librespot.audio.format.AudioQualityPicker { files ->
                    // Prefer OGG 320 → OGG 160 → anything OGG
                    files.firstOrNull { it.format == com.spotify.metadata.Metadata.AudioFile.Format.OGG_VORBIS_320 }
                        ?: files.firstOrNull { it.format == com.spotify.metadata.Metadata.AudioFile.Format.OGG_VORBIS_160 }
                        ?: files.firstOrNull { it.format == com.spotify.metadata.Metadata.AudioFile.Format.OGG_VORBIS_96 }
                        ?: files.firstOrNull()
                },
                false,
                null,
            )

            val stream: InputStream = loaded.`in`.stream()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            var n: Int
            while (stream.read(buf).also { n = it } != -1) {
                out.write(buf, 0, n)
            }
            stream.close()

            val bytes = out.toByteArray()
            if (bytes.isEmpty()) null else bytes
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "librespot download failed for $trackId")
            null
        } finally {
            runCatching { session?.close() }
        }
    }
}
