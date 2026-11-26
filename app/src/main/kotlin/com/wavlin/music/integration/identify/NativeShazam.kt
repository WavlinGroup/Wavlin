/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.integration.identify

import android.util.Base64
import timber.log.Timber

/**
 * JNI bridge to the Shazam signature generator and the resampler in `libwavlin_audio.so`
 * (Rust crate `rust/crates/shazam-core`).
 *
 * The Kotlin code in [ShazamSignatureGenerator] and [AudioResampler] stays the reference and the
 * fallback. Native output is not trusted from the start: the first call of each function in a
 * process runs the Kotlin code, runs the native code on the same input and compares the results.
 * Only if they are identical is the native code used for later calls. On any difference, exception
 * or missing library the Kotlin code keeps being used, so a bad native build cannot change what is
 * sent to Shazam. Every function returns null when native code is not available, not trusted yet,
 * or fails, and the caller then runs Kotlin.
 */
object NativeShazam {

    private const val TAG = "NativeShazam"
    private const val SIGNATURE_URI_PREFIX = "data:audio/vnd.shazam.sig;base64,"

    private enum class Trust { UNCHECKED, TRUSTED, REJECTED }

    @JvmField
    val isAvailable: Boolean = try {
        System.loadLibrary("wavlin_audio")
        true
    } catch (e: UnsatisfiedLinkError) {
        Timber.tag(TAG).w(e, "libwavlin_audio.so not loaded, using Kotlin signature and resampler")
        false
    }

    @Volatile
    private var signatureTrust = Trust.UNCHECKED

    @Volatile
    private var resampleTrust = Trust.UNCHECKED

    @JvmStatic
    external fun signatureNative(pcm: ByteArray): ByteArray?

    @JvmStatic
    external fun resampleNative(pcm: ByteArray, inputRate: Int, outputRate: Int): ByteArray?

    val needsSignatureCheck: Boolean
        get() = isAvailable && signatureTrust == Trust.UNCHECKED

    val needsResampleCheck: Boolean
        get() = isAvailable && resampleTrust == Trust.UNCHECKED

    /** Signature URI from native code, or null when Kotlin has to produce it. */
    fun trustedSignature(pcm: ByteArray): String? {
        if (!isAvailable || signatureTrust != Trust.TRUSTED) return null
        val bytes = try {
            signatureNative(pcm)
        } catch (e: UnsatisfiedLinkError) {
            null
        }
        if (bytes == null) return null
        return toSignatureUri(bytes)
    }

    /** Compares native output with [kotlinUri] for the same [pcm] and decides whether to trust native code. */
    fun verifySignature(pcm: ByteArray, kotlinUri: String) {
        val nativeUri = try {
            signatureNative(pcm)?.let { toSignatureUri(it) }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Native signature check threw, keeping the Kotlin generator")
            signatureTrust = Trust.REJECTED
            return
        } catch (e: UnsatisfiedLinkError) {
            Timber.tag(TAG).w(e, "Native signature missing, keeping the Kotlin generator")
            signatureTrust = Trust.REJECTED
            return
        }
        if (nativeUri == kotlinUri) {
            signatureTrust = Trust.TRUSTED
            Timber.tag(TAG).i("Native signature matches Kotlin (%d chars), using native from now on", kotlinUri.length)
        } else {
            signatureTrust = Trust.REJECTED
            Timber.tag(TAG).w(
                "Native signature differs from Kotlin (native=%s chars, kotlin=%d chars, first difference at %d), keeping the Kotlin generator",
                nativeUri?.length?.toString() ?: "null",
                kotlinUri.length,
                nativeUri?.let { firstDifference(it, kotlinUri) } ?: -1,
            )
        }
    }

    /** Resampled little-endian 16-bit mono PCM from native code, or null when Kotlin has to do it. */
    fun trustedResample(data: ByteArray, inputRate: Int, outputRate: Int): ByteArray? {
        if (!isAvailable || resampleTrust != Trust.TRUSTED) return null
        return try {
            resampleNative(data, inputRate, outputRate)
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    /** Compares native output with [kotlinOutput] for the same input and decides whether to trust native code. */
    fun verifyResample(data: ByteArray, inputRate: Int, outputRate: Int, kotlinOutput: ByteArray) {
        val nativeOutput = try {
            resampleNative(data, inputRate, outputRate)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Native resample check threw, keeping the Kotlin resampler")
            resampleTrust = Trust.REJECTED
            return
        } catch (e: UnsatisfiedLinkError) {
            Timber.tag(TAG).w(e, "Native resample missing, keeping the Kotlin resampler")
            resampleTrust = Trust.REJECTED
            return
        }
        if (nativeOutput != null && nativeOutput.contentEquals(kotlinOutput)) {
            resampleTrust = Trust.TRUSTED
            Timber.tag(TAG).i("Native resample matches Kotlin (%d bytes), using native from now on", kotlinOutput.size)
        } else {
            resampleTrust = Trust.REJECTED
            Timber.tag(TAG).w(
                "Native resample differs from Kotlin (native=%s bytes, kotlin=%d bytes), keeping the Kotlin resampler",
                nativeOutput?.size?.toString() ?: "null",
                kotlinOutput.size,
            )
        }
    }

    private fun toSignatureUri(bytes: ByteArray): String =
        SIGNATURE_URI_PREFIX + Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun firstDifference(a: String, b: String): Int {
        val common = minOf(a.length, b.length)
        for (i in 0 until common) {
            if (a[i] != b[i]) return i
        }
        return common
    }
}
