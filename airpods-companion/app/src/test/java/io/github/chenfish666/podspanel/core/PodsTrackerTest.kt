package io.github.chenfish666.podspanel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PodsTrackerTest {

    private fun status(
        model: Int = 0x3620, left: Int? = 90, case: Int? = null, lid: LidState = LidState.NOT_IN_CASE,
    ) = PodsStatus(
        modelCode = model, left = left, right = 80, case = case,
        leftCharging = false, rightCharging = false, caseCharging = false,
        leftInEar = false, rightInEar = false, lid = lid,
        connection = ConnectionState.IDLE, colorCode = 0,
    )

    @Test
    fun `a faint broadcast is not followed`() {
        val tracker = PodsTracker()
        assertFalse(tracker.offer(status(), rssi = -85, nowMs = 0))
        assertNull(tracker.snapshot(0))
    }

    @Test
    fun `a faint broadcast is followed when the caller knows the pair is connected`() {
        val tracker = PodsTracker()
        assertTrue(tracker.offer(status(), rssi = -85, nowMs = 0, minRssi = -92))
        assertEquals(90, tracker.snapshot(0)?.status?.left)
    }

    @Test
    fun `a nearby pair is followed`() {
        val tracker = PodsTracker()
        assertTrue(tracker.offer(status(), rssi = -50, nowMs = 0))
        assertEquals(90, tracker.snapshot(0)?.status?.left)
    }

    @Test
    fun `a stranger's pair of another model does not replace the followed one`() {
        val tracker = PodsTracker()
        tracker.offer(status(), rssi = -50, nowMs = 0)
        assertFalse(tracker.offer(status(model = 0x1420, left = 10), rssi = -55, nowMs = 1_000))
        assertEquals(90, tracker.snapshot(1_000)?.status?.left)
    }

    @Test
    fun `another pair takes over once the followed one has gone quiet`() {
        val tracker = PodsTracker()
        tracker.offer(status(), rssi = -50, nowMs = 0)
        assertTrue(tracker.offer(status(model = 0x1420, left = 10), rssi = -55, nowMs = 20_000))
        assertEquals(10, tracker.snapshot(20_000)?.status?.left)
    }

    @Test
    fun `case level is remembered after the earbuds leave the case`() {
        val tracker = PodsTracker()
        tracker.offer(status(case = 70, lid = LidState.OPEN), rssi = -50, nowMs = 0)
        tracker.offer(status(case = null), rssi = -50, nowMs = 1_000)
        assertEquals(70, tracker.snapshot(1_000)?.status?.case)
    }

    @Test
    fun `remembered case level is not handed to a different pair`() {
        val tracker = PodsTracker()
        tracker.offer(status(case = 70), rssi = -50, nowMs = 0)
        tracker.offer(status(model = 0x1420, case = null), rssi = -50, nowMs = 20_000)
        assertNull(tracker.snapshot(20_000)?.status?.case)
    }

    @Test
    fun `an unreliable lid reading keeps the last reliable one`() {
        val tracker = PodsTracker()
        tracker.offer(status(lid = LidState.CLOSED), rssi = -50, nowMs = 0)
        tracker.offer(status(lid = LidState.UNKNOWN), rssi = -50, nowMs = 1_000)
        assertEquals(LidState.CLOSED, tracker.snapshot(1_000)?.status?.lid)
    }

    @Test
    fun `levels are marked as old and later dropped when broadcasts stop`() {
        val tracker = PodsTracker()
        tracker.offer(status(), rssi = -50, nowMs = 0)
        assertTrue(tracker.snapshot(5_000)!!.live)
        val old = tracker.snapshot(30_000)
        assertNotNull(old)
        assertFalse(old!!.live)
        assertNull(tracker.snapshot(200_000))
    }
}
