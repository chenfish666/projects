package io.github.chenfish666.podspanel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Broadcasts recorded from real AirPods 5, as published in the CAPod project's test suite, with
 * the physical state each one was captured in.
 */
class ProximityParserTest {

    private fun parse(hex: String): PodsStatus? =
        ProximityParser.parse(hex.split(' ').map { it.toInt(16).toByte() }.toByteArray())

    @Test
    fun `AirPods 5 in case with lid open`() {
        val s = parse("07 19 01 36 20 15 99 F9 51 00 04 FB 0F 0C 65 33 51 1A CE 8F 04 32 66 9B D1 BD 4E")!!
        assertEquals(PodModel.AIRPODS_5, s.model)
        assertEquals(90, s.left)
        assertEquals(90, s.right)
        assertEquals(90, s.case)
        assertTrue(s.leftCharging && s.rightCharging && s.caseCharging)
        assertFalse(s.leftInEar || s.rightInEar)
        assertEquals(LidState.OPEN, s.lid)
        assertEquals(ConnectionState.IDLE, s.connection)
    }

    @Test
    fun `same state reads the same when the other earbud broadcasts`() {
        val s = parse("07 19 01 36 20 75 99 F9 51 00 04 FE FE 67 AA BA BE 80 C9 C3 2E 81 10 9E CD A7 46")!!
        assertEquals(PodModel.AIRPODS_5, s.model)
        assertEquals(90, s.left)
        assertEquals(90, s.right)
        assertFalse(s.leftInEar || s.rightInEar)
        assertEquals(LidState.OPEN, s.lid)
    }

    @Test
    fun `AirPods 5 in case with lid closed`() {
        val s = parse("07 19 01 36 20 15 99 F9 59 00 04 E1 5E 66 15 6F 0B 36 9A C6 17 43 56 B7 34 28 7D")!!
        assertEquals(LidState.CLOSED, s.lid)
    }

    @Test
    fun `AirPods 5 not connected to anything`() {
        val s = parse("07 19 01 36 20 14 99 F9 58 00 00 C8 84 3F AE C5 17 15 92 3A 14 B6 65 31 84 66 86")!!
        assertEquals(ConnectionState.DISCONNECTED, s.connection)
        assertEquals(LidState.CLOSED, s.lid)
    }

    @Test
    fun `wireless case model with different levels per earbud`() {
        val s = parse("07 19 01 30 20 55 A9 B9 5A 00 04 7E 88 A7 35 54 C2 34 41 4C 46 EC 23 9E BD A6 14")!!
        assertEquals(PodModel.AIRPODS_5_WIRELESS, s.model)
        assertEquals(100, s.left)
        assertEquals(90, s.right)
        assertEquals(90, s.case)
        assertTrue(s.leftCharging && s.rightCharging)
        assertFalse(s.caseCharging)
        assertEquals(LidState.CLOSED, s.lid)
    }

    @Test
    fun `levels stay with their earbud when the primary earbud changes`() {
        // The battery byte is 9A here and A9 above, but the primary bit flipped as well.
        val s = parse("07 19 01 30 20 25 9A B9 53 00 00 2A 46 EA D2 5A A6 33 38 D8 23 69 BD 57 C9 65 F7")!!
        assertEquals(100, s.left)
        assertEquals(90, s.right)
        assertTrue(s.leftCharging && s.rightCharging)
        assertEquals(LidState.OPEN, s.lid)
        assertEquals(ConnectionState.DISCONNECTED, s.connection)
    }

    @Test
    fun `one earbud worn and one charging`() {
        val s = parse("07 19 01 30 20 13 A9 A9 13 00 04 55 94 59 C6 1C C9 6D 13 4F EA D9 52 71 AE EB E4")!!
        assertFalse(s.leftInEar)
        assertTrue(s.rightInEar)
        assertEquals(100, s.left)
        assertEquals(90, s.right)
        assertTrue(s.leftCharging)
        assertFalse(s.rightCharging)
        // Sent by the earbud outside the case, whose lid byte is stale.
        assertEquals(LidState.UNKNOWN, s.lid)
    }

    @Test
    fun `both earbuds worn while playing`() {
        val s = parse("07 19 01 30 20 0B A9 8F 13 00 05 EA DB 3C 0E 2E 09 42 A7 22 FF B6 CD D7 FD E8 1A")!!
        assertTrue(s.leftInEar && s.rightInEar)
        assertEquals(100, s.left)
        assertEquals(90, s.right)
        assertNull(s.case)
        assertFalse(s.leftCharging || s.rightCharging || s.caseCharging)
        assertEquals(LidState.NOT_IN_CASE, s.lid)
        assertEquals(ConnectionState.MUSIC, s.connection)
    }

    @Test
    fun `status record is found behind another record`() {
        val s = parse("10 02 0B 00 07 19 01 36 20 15 99 F9 51 00 04 FB 0F 0C 65 33 51 1A CE 8F 04 32 66 9B D1 BD 4E")
        assertEquals(PodModel.AIRPODS_5, s?.model)
    }

    @Test
    fun `data that is not a status broadcast is rejected`() {
        assertNull(ProximityParser.parse(null))
        assertNull(ProximityParser.parse(ByteArray(0)))
        // Another Apple record type (nearby info).
        assertNull(parse("10 05 0B 1C 5E 2A 11"))
        // Right type, truncated body.
        assertNull(parse("07 19 01 36 20 15"))
        // Right type and length, but not the plaintext status format.
        assertNull(parse("07 19 07 36 20 15 99 F9 51 00 04 FB 0F 0C 65 33 51 1A CE 8F 04 32 66 9B D1 BD 4E"))
    }

    @Test
    fun `unknown model is still decoded`() {
        val s = parse("07 19 01 7F 20 0B A9 8F 13 00 05 EA DB 3C 0E 2E 09 42 A7 22 FF B6 CD D7 FD E8 1A")!!
        assertEquals(PodModel.UNKNOWN, s.model)
        assertEquals(100, s.left)
    }
}
