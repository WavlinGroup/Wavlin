/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.playback

import timber.log.Timber

/**
 * JNI bridge to the shuffle permutations in `libwavlin_audio.so` (Rust crate `rust/crates/queue-core`).
 *
 * Both functions return null when the library is missing, the native call fails or the input is not
 * a valid queue state, and [MusicService] then runs its Kotlin implementation. The player, the
 * timeline traversal and `DefaultShuffleOrder` stay in Kotlin.
 */
object NativeQueue {

    private const val TAG = "NativeQueue"

    @JvmField
    val isAvailable: Boolean = try {
        System.loadLibrary("wavlin_audio")
        true
    } catch (e: UnsatisfiedLinkError) {
        Timber.tag(TAG).w(e, "libwavlin_audio.so not loaded, using Kotlin shuffle")
        false
    }

    @JvmStatic
    external fun shuffleOrderNative(
        current: Int,
        total: Int,
        playlistFirst: Boolean,
        originalSize: Int,
        seed: Long,
    ): IntArray?

    @JvmStatic
    external fun playNextOrderNative(
        prev: IntArray,
        after: IntArray,
        current: Int,
        insertIndex: Int,
        itemCount: Int,
        size: Int,
    ): IntArray?

    fun shuffleOrder(current: Int, total: Int, playlistFirst: Boolean, originalSize: Int): IntArray? {
        if (!isAvailable) return null
        return try {
            shuffleOrderNative(current, total, playlistFirst, originalSize, System.nanoTime() xor System.currentTimeMillis())
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    fun playNextOrder(prev: List<Int>, after: List<Int>, current: Int, insertIndex: Int, itemCount: Int, size: Int): IntArray? {
        if (!isAvailable) return null
        return try {
            playNextOrderNative(prev.toIntArray(), after.toIntArray(), current, insertIndex, itemCount, size)
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }
}
