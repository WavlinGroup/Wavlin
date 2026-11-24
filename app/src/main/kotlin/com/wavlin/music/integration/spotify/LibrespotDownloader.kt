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
import xyz.gianlu.librespot.audio.format.AudioQualityPicker
import xyz.gianlu.librespot.audio.format.SuperAudioFormat
import xyz.gianlu.librespot.core.Session
import xyz.gianlu.librespot.metadata.TrackId
import com.spotify.metadata.Metadata
import java.io.ByteArrayOutputStream
import java.io.InputStream

object LibrespotDownloader {

    private const val TAG = "LibrespotDownloader"

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
            val credentials = Authentication.LoginCredentials.newBuilder()
                .setUsername("")
                .setTyp(Authentication.AuthenticationType.AUTHENTICATION_SPOTIFY_TOKEN)
                .setAuthData(ByteString.copyFromUtf8(accessToken))
                .build()

            val conf = Session.Configuration.Builder()
                .setStoreCredentials(false)
                .build()

            Timber.tag(TAG).d("Building librespot session for track $trackId")

            session = Session.Builder(conf)
                .credentials(credentials)
                .create()

            Timber.tag(TAG).d("Session created, authenticated as ${session.username()}")

            val id = TrackId.fromBase62(trackId)

            // Quality picker used for both the standard path and the bypass path.
            // The bypass path iterates formats internally in descending quality order and ignores
            // this picker's selection — it only uses the picker when the standard load succeeds.
            val qualityPicker = AudioQualityPicker { files ->
                files.firstOrNull { it.format == Metadata.AudioFile.Format.OGG_VORBIS_320 }
                    ?: files.firstOrNull { it.format == Metadata.AudioFile.Format.OGG_VORBIS_160 }
                    ?: files.firstOrNull { it.format == Metadata.AudioFile.Format.OGG_VORBIS_96 }
                    ?: files.firstOrNull()
            }

            // Use session's own content feeder (initialised after auth).
            // For free accounts the standard load() throws ContentRestrictedException (metadata has
            // no files) or propagates an IllegalStateException from storage-resolve returning
            // RESTRICTED.  Either way we fall through to loadForFreeAccount() which probes four
            // different storage-resolve endpoints and iterates every file format.
            val feeder = session.contentFeeder()
            val loaded = try {
                feeder.load(id, qualityPicker, false, null)
            } catch (e: xyz.gianlu.librespot.audio.PlayableContentFeeder.ContentRestrictedException) {
                Timber.tag(TAG).w("Standard load restricted for $trackId — trying free-account bypass")
                feeder.loadForFreeAccount(id, qualityPicker, false, null)
            } catch (e: IllegalStateException) {
                // storage-resolve returned RESTRICTED (thrown as ISE inside loadStream)
                Timber.tag(TAG).w("storage-resolve restricted for $trackId (${e.message}) — trying free-account bypass")
                feeder.loadForFreeAccount(id, qualityPicker, false, null)
            }

            Timber.tag(TAG).d("Track loaded, reading stream")

            val stream: InputStream = loaded.`in`.stream()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            var n: Int
            while (stream.read(buf).also { n = it } != -1) {
                out.write(buf, 0, n)
            }
            stream.close()

            val bytes = out.toByteArray()
            Timber.tag(TAG).d("Stream read complete: ${bytes.size} bytes")

            if (bytes.isEmpty()) {
                Timber.tag(TAG).w("Stream was empty for track $trackId")
                null
            } else {
                bytes
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: xyz.gianlu.librespot.audio.PlayableContentFeeder.ContentRestrictedException) {
            Timber.tag(TAG).w("Track $trackId is content-restricted (Premium required or geo-blocked)")
            null
        } catch (e: Session.SpotifyAuthenticationException) {
            Timber.tag(TAG).e(e, "Spotify AP auth rejected for track $trackId — token may lack streaming scope or account isn't Premium")
            null
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "librespot download failed for $trackId [${e::class.java.simpleName}]: ${e.message}")
            null
        } finally {
            runCatching { session?.close() }
        }
    }
}
