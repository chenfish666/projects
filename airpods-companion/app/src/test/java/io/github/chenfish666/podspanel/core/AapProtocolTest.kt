package io.github.chenfish666.podspanel.core

import io.github.chenfish666.podspanel.core.AapProtocol.Event
import io.github.chenfish666.podspanel.core.AapProtocol.Placement
import io.github.chenfish666.podspanel.core.AapProtocol.Side
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AapProtocolTest {

    private fun frame(hex: String) = hex.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `battery message with both earbuds and the case`() {
        // Left 100% in use, right 99% charging, case 17% not charging.
        val event = AapProtocol.parse(
            frame("04 00 04 00 04 00 03 04 01 64 02 01 02 01 63 01 01 08 01 11 02 01")
        ) as Event.Battery
        assertEquals(AapProtocol.Level(100, charging = false), event.left)
        assertEquals(AapProtocol.Level(99, charging = true), event.right)
        assertEquals(AapProtocol.Level(17, charging = false), event.case)
        assertEquals(Side.LEFT, event.first)
    }

    @Test
    fun `placeholder levels and absent parts are left out`() {
        // Right earbud disconnected, case reporting the 127 placeholder it sends after closing.
        val event = AapProtocol.parse(
            frame("04 00 04 00 04 00 03 02 01 50 04 01 04 01 5A 02 01 08 01 7F 02 01")
        ) as Event.Battery
        assertNull(event.right)
        assertEquals(90, event.left?.percent)
        assertNull(event.case)
        assertEquals(Side.RIGHT, event.first)
    }

    @Test
    fun `optimized charging counts as charging`() {
        val event = AapProtocol.parse(frame("04 00 04 00 04 00 01 08 01 50 05 01")) as Event.Battery
        assertTrue(event.case!!.charging)
    }

    @Test
    fun `in-ear, role and listening mode messages`() {
        assertEquals(Event.Ear(Placement.IN_EAR, Placement.OUT), AapProtocol.parse(frame("04 00 04 00 06 00 00 01")))
        assertEquals(Event.Ear(Placement.IN_CASE, Placement.IN_CASE), AapProtocol.parse(frame("04 00 04 00 06 00 02 02")))
        assertEquals(Event.Role(Side.RIGHT), AapProtocol.parse(frame("04 00 04 00 08 00 02 00 01 00")))
        assertEquals(
            Event.Mode(AapProtocol.ListeningMode.TRANSPARENCY),
            AapProtocol.parse(frame("04 00 04 00 09 00 0D 03 00 00 00")),
        )
    }

    @Test
    fun `other settings, other messages and fragments are ignored`() {
        assertNull(AapProtocol.parse(frame("04 00 04 00 09 00 1A 01 00 00 00")))
        assertNull(AapProtocol.parse(frame("04 00 04 00 4B 00 02 00 01 01")))
        assertNull(AapProtocol.parse(frame("04 00 04")))
        assertNull(AapProtocol.parse(frame("04 00 04 00 06 00 00")))
    }

    @Test
    fun `answer to the handshake is recognised`() {
        assertEquals(Event.Connected(0), AapProtocol.parse(frame("01 00 04 00 00 00 01 00 02 00")))
    }

    @Test
    fun `command to change the listening mode`() {
        assertArrayEquals(
            frame("04 00 04 00 09 00 0D 02 00 00 00"),
            AapProtocol.setListeningMode(AapProtocol.ListeningMode.NOISE_CANCELLATION),
        )
    }
}

class AapStateTest {

    private fun battery(left: Int?, right: Int?, case: Int? = null, first: Side? = Side.LEFT) = Event.Battery(
        left = left?.let { AapProtocol.Level(it, false) },
        right = right?.let { AapProtocol.Level(it, false) },
        case = case?.let { AapProtocol.Level(it, true) },
        single = null,
        first = first,
    )

    @Test
    fun `nothing to show before a level is known`() {
        val state = AapState()
        state.apply(Event.Ear(Placement.IN_EAR, Placement.IN_EAR))
        assertNull(state.toStatus(modelCode = -1, lid = LidState.UNKNOWN))
    }

    @Test
    fun `levels and placement are combined into one status`() {
        val state = AapState()
        state.apply(battery(left = 87, right = 91, case = 60))
        state.apply(Event.Ear(primary = Placement.IN_EAR, secondary = Placement.OUT))
        val status = state.toStatus(modelCode = 0x3620, lid = LidState.UNKNOWN)!!
        assertEquals(87, status.left)
        assertEquals(91, status.right)
        assertEquals(60, status.case)
        assertTrue(status.caseCharging)
        assertTrue(status.leftInEar)
        assertFalse(status.rightInEar)
        assertEquals(PodModel.AIRPODS_5, status.model)
        assertEquals(1, state.wornCount)
    }

    @Test
    fun `placement follows the earbud announced as primary`() {
        val state = AapState()
        state.apply(battery(left = 80, right = 80))
        state.apply(Event.Role(Side.RIGHT))
        state.apply(Event.Ear(primary = Placement.IN_EAR, secondary = Placement.IN_CASE))
        val status = state.toStatus(-1, LidState.UNKNOWN)!!
        assertTrue(status.rightInEar)
        assertFalse(status.leftInEar)
    }

    @Test
    fun `case level is kept when later messages omit it`() {
        val state = AapState()
        state.apply(battery(left = 80, right = 80, case = 55))
        state.apply(battery(left = 79, right = 80, case = null))
        assertEquals(55, state.toStatus(-1, LidState.UNKNOWN)?.case)
    }

    @Test
    fun `an earbud that drops out loses its level`() {
        val state = AapState()
        state.apply(battery(left = 80, right = 80))
        state.apply(battery(left = 80, right = null))
        assertNull(state.toStatus(-1, LidState.UNKNOWN)?.right)
    }

    @Test
    fun `in-ear state is unknown until reported, and reset clears everything`() {
        val state = AapState()
        assertNull(state.wornCount)
        state.apply(battery(left = 80, right = 80))
        state.apply(Event.Mode(AapProtocol.ListeningMode.ADAPTIVE))
        state.apply(Event.Ear(Placement.IN_EAR, Placement.IN_EAR))
        assertEquals(2, state.wornCount)
        state.reset()
        assertNull(state.wornCount)
        assertNull(state.mode)
        assertNull(state.toStatus(-1, LidState.UNKNOWN))
    }
}

class StoredLevelsTest {

    @Test
    fun `levels survive being stored`() {
        val status = PodsStatus(
            modelCode = 0x3620, left = 87, right = null, case = 60,
            leftCharging = false, rightCharging = false, caseCharging = true,
            leftInEar = true, rightInEar = false, lid = LidState.OPEN,
            connection = ConnectionState.MUSIC, colorCode = 3,
        )
        val restored = PodsStatus.decodeLevels(status.encodeLevels())!!
        assertEquals(0x3620, restored.modelCode)
        assertEquals(87, restored.left)
        assertNull(restored.right)
        assertEquals(60, restored.case)
        assertTrue(restored.caseCharging)
        // Momentary state is not carried over.
        assertFalse(restored.leftInEar)
        assertEquals(LidState.UNKNOWN, restored.lid)
    }

    @Test
    fun `missing or damaged text restores nothing`() {
        assertNull(PodsStatus.decodeLevels(null))
        assertNull(PodsStatus.decodeLevels(""))
        assertNull(PodsStatus.decodeLevels("x,1,2,3,true,false,false"))
        assertNull(PodsStatus.decodeLevels("1,2,3"))
    }
}
