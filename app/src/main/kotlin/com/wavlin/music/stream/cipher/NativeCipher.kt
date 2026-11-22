/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.stream.cipher

import timber.log.Timber

/**
 * JNI bridge to the player.js scanners in `libwavlin_audio.so` (Rust crate `rust/crates/cipher-core`).
 *
 * Only the regex heuristics run natively. Config lookup, precedence and logging stay in
 * [FunctionNameExtractor]. Every function returns null when the library is missing or the native
 * call fails, so the caller runs its Kotlin regex. A non-null [Scan] with a null value means the
 * native scan ran and found nothing.
 */
object NativeCipher {

    private const val TAG = "NativeCipher"

    class Scan<T>(val value: T?)

    @JvmField
    val isAvailable: Boolean = try {
        System.loadLibrary("wavlin_audio")
        true
    } catch (e: UnsatisfiedLinkError) {
        Timber.tag(TAG).w(e, "libwavlin_audio.so not loaded, using Kotlin cipher scanners")
        false
    }

    @JvmStatic external fun hasQArrayNative(js: String): Array<String?>?
    @JvmStatic external fun findPlayerHashNative(js: String): Array<String?>?
    @JvmStatic external fun findSignatureTimestampNative(js: String, anchored: Boolean): Array<String?>?
    @JvmStatic external fun findSigFunctionNative(js: String): Array<String?>?
    @JvmStatic external fun findNFunctionNative(js: String): Array<String?>?

    private inline fun call(block: () -> Array<String?>?): Array<String?>? {
        if (!isAvailable) return null
        return try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    fun hasQArray(js: String): Boolean? {
        val r = call { hasQArrayNative(js) } ?: return null
        return when (r.singleOrNull()) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }

    fun findPlayerHash(js: String): Scan<String>? {
        val r = call { findPlayerHashNative(js) } ?: return null
        return when (r.size) {
            0 -> Scan(null)
            1 -> Scan(r[0] ?: return null)
            else -> null
        }
    }

    fun findSignatureTimestamp(js: String, anchored: Boolean): Scan<Int>? {
        val r = call { findSignatureTimestampNative(js, anchored) } ?: return null
        return when (r.size) {
            0 -> Scan(null)
            1 -> Scan((r[0] ?: return null).toIntOrNull() ?: return null)
            else -> null
        }
    }

    fun findSigFunction(js: String): Scan<FunctionNameExtractor.SigFunctionInfo>? {
        val r = call { findSigFunctionNative(js) } ?: return null
        return when (r.size) {
            0 -> Scan(null)
            2 -> Scan(
                FunctionNameExtractor.SigFunctionInfo(
                    name = r[0] ?: return null,
                    constantArg = r[1]?.toIntOrNull(),
                    isHardcoded = false,
                ),
            )
            else -> null
        }
    }

    fun findNFunction(js: String): Scan<FunctionNameExtractor.NFunctionInfo>? {
        val r = call { findNFunctionNative(js) } ?: return null
        return when (r.size) {
            0 -> Scan(null)
            2 -> Scan(
                FunctionNameExtractor.NFunctionInfo(
                    name = r[0] ?: return null,
                    arrayIndex = r[1]?.toIntOrNull(),
                    isHardcoded = false,
                ),
            )
            else -> null
        }
    }
}
