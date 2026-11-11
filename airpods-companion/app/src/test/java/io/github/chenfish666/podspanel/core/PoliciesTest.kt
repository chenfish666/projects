package io.github.chenfish666.podspanel.core

import io.github.chenfish666.podspanel.core.EarAutoPause.Action
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmPolicyTest {

    private fun input(
        mode: AlarmMode, playing: Boolean = true, headphones: Boolean = true,
        alreadyOnHeadphones: Boolean = false, inEar: Boolean? = true,
    ) = AlarmPolicy.Input(mode, playing, headphones, alreadyOnHeadphones, inEar)

    @Test
    fun `nothing happens when off, when no alarm rings, or without headphones`() {
        assertEquals(AlarmPolicy.NOTHING, AlarmPolicy.decide(input(AlarmMode.OFF)))
        assertEquals(AlarmPolicy.NOTHING, AlarmPolicy.decide(input(AlarmMode.MIRROR, playing = false)))
        assertEquals(AlarmPolicy.NOTHING, AlarmPolicy.decide(input(AlarmMode.HEADPHONES_ONLY, headphones = false)))
    }

    @Test
    fun `mirror mode plays a copy and leaves the speaker alone`() {
        val d = AlarmPolicy.decide(input(AlarmMode.MIRROR))
        assertTrue(d.mirror)
        assertFalse(d.quietSpeaker)
    }

    @Test
    fun `mirror mode does not double an alarm the system already sends to the headphones`() {
        assertFalse(AlarmPolicy.decide(input(AlarmMode.MIRROR, alreadyOnHeadphones = true)).mirror)
    }

    @Test
    fun `headphones-only mode quietens the speaker only while an earbud is worn`() {
        val worn = AlarmPolicy.decide(input(AlarmMode.HEADPHONES_ONLY, inEar = true))
        assertTrue(worn.mirror && worn.quietSpeaker)

        for (inEar in listOf(false, null)) {
            val notWorn = AlarmPolicy.decide(input(AlarmMode.HEADPHONES_ONLY, inEar = inEar))
            assertTrue(notWorn.mirror)
            assertFalse(notWorn.quietSpeaker)
        }
    }

    @Test
    fun `headphones-only mode still mirrors when the system routes the alarm there itself`() {
        // The system's own copy becomes quiet together with the speaker.
        val d = AlarmPolicy.decide(input(AlarmMode.HEADPHONES_ONLY, alreadyOnHeadphones = true))
        assertTrue(d.mirror && d.quietSpeaker)
    }
}

class EarAutoPauseTest {

    private fun EarAutoPause.feed(worn: Int, music: Boolean, at: Long = 0) =
        listOf(onReading(worn, music, at), onReading(worn, music, at)).last()

    @Test
    fun `taking an earbud out pauses and putting it back resumes`() {
        val ear = EarAutoPause()
        assertEquals(Action.NONE, ear.feed(2, music = true))
        assertEquals(Action.PAUSE, ear.feed(1, music = true))
        assertEquals(Action.PLAY, ear.feed(2, music = false, at = 10_000))
    }

    @Test
    fun `a single stray reading is ignored`() {
        val ear = EarAutoPause()
        ear.feed(2, music = true)
        assertEquals(Action.NONE, ear.onReading(1, true, 0))
        assertEquals(Action.NONE, ear.onReading(2, true, 0))
        assertEquals(Action.NONE, ear.onReading(2, true, 0))
    }

    @Test
    fun `nothing is resumed that this class did not pause`() {
        val ear = EarAutoPause()
        ear.feed(2, music = false)
        assertEquals(Action.NONE, ear.feed(1, music = false))
        assertEquals(Action.NONE, ear.feed(2, music = false))
    }

    @Test
    fun `playback is not resumed long after the pause`() {
        val ear = EarAutoPause()
        ear.feed(2, music = true)
        assertEquals(Action.PAUSE, ear.feed(1, music = true, at = 0))
        assertEquals(Action.NONE, ear.feed(2, music = false, at = 600_000))
    }

    @Test
    fun `taking out the second earbud after a pause does not resume`() {
        val ear = EarAutoPause()
        ear.feed(2, music = true)
        assertEquals(Action.PAUSE, ear.feed(1, music = true))
        assertEquals(Action.NONE, ear.feed(0, music = false))
        assertEquals(Action.PLAY, ear.feed(2, music = false, at = 5_000))
    }
}

class EqMathTest {

    @Test
    fun `band edges rise and end at the top of hearing`() {
        val cutoffs = EqMath.CUTOFFS
        assertEquals(EqMath.BAND_COUNT, cutoffs.size)
        for (i in 1 until cutoffs.size) assertTrue(cutoffs[i] > cutoffs[i - 1])
        for (i in 0 until cutoffs.size - 1) {
            assertTrue(cutoffs[i] > EqMath.CENTERS[i] && cutoffs[i] < EqMath.CENTERS[i + 1])
        }
        assertEquals(20_000f, cutoffs.last(), 0f)
    }

    @Test
    fun `preamp offsets the largest boost and ignores cuts`() {
        assertEquals(-7f, EqMath.preampDb(EqMath.presetById("bass")!!.gains), 0f)
        assertEquals(0f, EqMath.preampDb(floatArrayOf(-3f, -1f, 0f)), 0f)
    }

    @Test
    fun `every preset has ten bands within range`() {
        for (preset in EqMath.PRESETS) {
            assertEquals(preset.id, EqMath.BAND_COUNT, preset.gains.size)
            assertTrue(preset.id, preset.gains.all { it in -EqMath.MAX_GAIN_DB..EqMath.MAX_GAIN_DB })
        }
    }

    @Test
    fun `gains survive being stored`() {
        val gains = EqMath.presetById("vocal")!!.gains
        assertArrayEquals(gains, EqMath.decode(EqMath.encode(gains)), 0f)
    }

    @Test
    fun `missing or damaged stored gains fall back to flat`() {
        assertArrayEquals(FloatArray(10), EqMath.decode(null), 0f)
        assertArrayEquals(FloatArray(10), EqMath.decode("abc,,"), 0f)
        assertEquals(12f, EqMath.decode("99")[0], 0f)
    }

    @Test
    fun `curve is interpolated between band centres`() {
        val gains = floatArrayOf(0f, 0f, 0f, 0f, 0f, 4f, 8f, 0f, 0f, 0f)
        assertEquals(4f, EqMath.gainAt(1000f, gains), 0.001f)
        // The geometric midpoint of 1 kHz and 2 kHz lies halfway on a logarithmic axis.
        assertEquals(6f, EqMath.gainAt(1414.2f, gains), 0.01f)
        assertEquals(0f, EqMath.gainAt(10f, gains), 0f)
        assertEquals(0f, EqMath.gainAt(30_000f, gains), 0f)
    }
}
