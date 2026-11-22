/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.stream.cipher

/**
 * JNI bridge to the player-config validator in `libwavlin_audio.so` (Rust crate
 * `rust/crates/player-config-core`).
 *
 * Only parsing and validation run natively. The n-transform JavaScript and the final config map are
 * still built in [PlayerConfigParser], so nothing from the file is ever evaluated as-is. [parse]
 * returns null when the library is missing, the native call fails, or the reply does not decode, and
 * the caller then runs its Kotlin parser. No Android or Timber imports, so JVM unit tests load this
 * object without the library and fall back.
 *
 * Wire format (flat `String[]`, mirrors `player_config_core::to_wire`):
 * - `["F", reason]`
 * - `["S", skippedCount, skipped..., entryCount, (hash, sig, nClass, sts, aliasCount, aliases...)*]`
 */
object NativePlayerConfig {

    class Entry(
        val hash: String,
        val sig: String,
        val nClass: String,
        val sts: Int,
        val aliases: List<String>,
    )

    sealed class Decoded {
        class Success(val entries: List<Entry>, val skipped: List<String>) : Decoded()
        class Failure(val reason: String) : Decoded()
    }

    @JvmField
    val isAvailable: Boolean = try {
        System.loadLibrary("wavlin_audio")
        true
    } catch (e: UnsatisfiedLinkError) {
        false
    }

    @JvmStatic external fun parseNative(json: String): Array<String?>?

    fun parse(json: String): Decoded? {
        if (!isAvailable) return null
        val wire = try {
            parseNative(json)
        } catch (e: UnsatisfiedLinkError) {
            null
        } ?: return null
        return decode(wire)
    }

    /** Pure and bounds-checked: any malformed or truncated reply yields null. */
    internal fun decode(wire: Array<String?>): Decoded? {
        var pos = 0
        fun next(): String? = wire.getOrNull(pos++)
        fun nextCount(): Int? = next()?.toIntOrNull()?.takeIf { it >= 0 && it <= wire.size - pos }

        when (next()) {
            "F" -> {
                val reason = next() ?: return null
                return if (pos == wire.size) Decoded.Failure(reason) else null
            }
            "S" -> Unit
            else -> return null
        }

        val skippedCount = nextCount() ?: return null
        val skipped = List(skippedCount) { next() ?: return null }

        val entryCount = nextCount() ?: return null
        val entries = ArrayList<Entry>(entryCount)
        repeat(entryCount) {
            val hash = next() ?: return null
            val sig = next() ?: return null
            val nClass = next() ?: return null
            val sts = next()?.toIntOrNull() ?: return null
            val aliasCount = nextCount() ?: return null
            val aliases = List(aliasCount) { next() ?: return null }
            entries += Entry(hash, sig, nClass, sts, aliases)
        }
        return if (pos == wire.size) Decoded.Success(entries, skipped) else null
    }
}
