package io.github.chenfish666.podspanel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnPairFilterTest {

    private val mine = 0x3020
    private val strangers = 0x2720

    @Test
    fun `nothing is shown before any AirPods have connected`() {
        val filter = OwnPairFilter()
        assertFalse(filter.accept(strangers, rssi = -40, connected = false, nowMs = 0))
        assertEquals(-1, filter.model)
    }

    @Test
    fun `the pair being worn is learnt from a strong broadcast while connected`() {
        val filter = OwnPairFilter()
        assertTrue(filter.accept(mine, rssi = -37, connected = true, nowMs = 0))
        assertEquals(mine, filter.model)
    }

    @Test
    fun `a faint broadcast while connected is not learnt from`() {
        val filter = OwnPairFilter()
        assertFalse(filter.accept(strangers, rssi = -85, connected = true, nowMs = 0))
        assertEquals(-1, filter.model)
    }

    @Test
    fun `another model is dropped once the own pair is known`() {
        val filter = OwnPairFilter(model = mine)
        assertFalse(filter.accept(strangers, rssi = -63, connected = true, nowMs = 0))
        assertFalse(filter.accept(strangers, rssi = -63, connected = false, nowMs = 0))
    }

    @Test
    fun `while disconnected the own model only counts right next to the phone`() {
        val filter = OwnPairFilter(model = mine)
        // Someone else's pair of the same model across the room.
        assertFalse(filter.accept(mine, rssi = -75, connected = false, nowMs = 0))
        // The user's case opened next to the phone.
        assertTrue(filter.accept(mine, rssi = -45, connected = false, nowMs = 0))
    }

    @Test
    fun `while connected the own model counts at any strength`() {
        val filter = OwnPairFilter(model = mine)
        assertTrue(filter.accept(mine, rssi = -80, connected = true, nowMs = 0))
    }

    @Test
    fun `a strong stranger does not replace a pair that is still being heard`() {
        val filter = OwnPairFilter(model = mine)
        assertTrue(filter.accept(mine, rssi = -50, connected = true, nowMs = 0))
        assertFalse(filter.accept(strangers, rssi = -40, connected = true, nowMs = 5_000))
        assertEquals(mine, filter.model)
    }

    @Test
    fun `new AirPods are learnt after the old ones have gone quiet`() {
        val filter = OwnPairFilter(model = mine)
        assertTrue(filter.accept(mine, rssi = -50, connected = true, nowMs = 0))
        assertTrue(filter.accept(strangers, rssi = -40, connected = true, nowMs = 60_000))
        assertEquals(strangers, filter.model)
    }

    @Test
    fun `nothing is relearnt while no AirPods are connected`() {
        val filter = OwnPairFilter(model = mine)
        assertFalse(filter.accept(strangers, rssi = -30, connected = false, nowMs = 60_000))
        assertEquals(mine, filter.model)
    }
}
