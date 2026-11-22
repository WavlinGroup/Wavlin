package com.wavlin.music.stream.cipher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NativePlayerConfig.decode] sits between a native reply and the cipher config table, so any
 * malformed or truncated reply must decode to null (Kotlin parser takes over) rather than to a
 * partial table.
 */
class NativePlayerConfigDecodeTest {

    private fun wire(vararg items: String?): Array<String?> = arrayOf(*items)

    @Test
    fun `failure reply decodes to its reason`() {
        val decoded = NativePlayerConfig.decode(wire("F", "players missing or not an object"))
        assertEquals("players missing or not an object", (decoded as NativePlayerConfig.Decoded.Failure).reason)
    }

    @Test
    fun `success reply decodes entries aliases and skipped hashes`() {
        val decoded = NativePlayerConfig.decode(
            wire(
                "S", "1", "bbbbbbbb",
                "2",
                "aaaaaaaa", "a(1,2,INPUT)", "A", "7", "1", "11112222",
                "cccccccc", "c(3,4,INPUT)", "C", "9", "0",
            ),
        ) as NativePlayerConfig.Decoded.Success

        assertEquals(listOf("bbbbbbbb"), decoded.skipped)
        assertEquals(2, decoded.entries.size)
        val first = decoded.entries[0]
        assertEquals("aaaaaaaa", first.hash)
        assertEquals("a(1,2,INPUT)", first.sig)
        assertEquals("A", first.nClass)
        assertEquals(7, first.sts)
        assertEquals(listOf("11112222"), first.aliases)
        assertTrue(decoded.entries[1].aliases.isEmpty())
    }

    @Test
    fun `empty success reply decodes`() {
        val decoded = NativePlayerConfig.decode(wire("S", "0", "0")) as NativePlayerConfig.Decoded.Success
        assertTrue(decoded.entries.isEmpty() && decoded.skipped.isEmpty())
    }

    @Test
    fun `malformed replies decode to null`() {
        assertNull(NativePlayerConfig.decode(wire()))
        assertNull(NativePlayerConfig.decode(wire("X")))
        assertNull(NativePlayerConfig.decode(wire(null)))
        assertNull(NativePlayerConfig.decode(wire("F")))
        assertNull(NativePlayerConfig.decode(wire("F", "reason", "extra")))
        assertNull(NativePlayerConfig.decode(wire("S")))
        assertNull(NativePlayerConfig.decode(wire("S", "0")))
        assertNull(NativePlayerConfig.decode(wire("S", "0", "0", "trailing")))
        assertNull(NativePlayerConfig.decode(wire("S", "-1", "0")))
        assertNull(NativePlayerConfig.decode(wire("S", "x", "0")))
        // Counts larger than the remaining reply must not loop or allocate on trust.
        assertNull(NativePlayerConfig.decode(wire("S", "2147483647", "0")))
        assertNull(NativePlayerConfig.decode(wire("S", "0", "1", "aaaaaaaa", "sig", "A", "7", "5")))
        // Truncated entry, bad sts, null element.
        assertNull(NativePlayerConfig.decode(wire("S", "0", "1", "aaaaaaaa", "sig", "A")))
        assertNull(NativePlayerConfig.decode(wire("S", "0", "1", "aaaaaaaa", "sig", "A", "x", "0")))
        assertNull(NativePlayerConfig.decode(wire("S", "0", "1", "aaaaaaaa", null, "A", "7", "0")))
    }
}
