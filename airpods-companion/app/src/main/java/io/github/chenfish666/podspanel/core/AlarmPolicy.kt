package io.github.chenfish666.podspanel.core

enum class AlarmMode(val id: String) {
    OFF("off"),

    /** Also ring in the headphones; the phone speaker is left alone. */
    MIRROR("mirror"),

    /** Ring in the headphones and turn the phone speaker down as far as the system allows. */
    HEADPHONES_ONLY("only");

    companion object {
        fun fromId(id: String?): AlarmMode = entries.firstOrNull { it.id == id } ?: MIRROR
    }
}

/** Decides what to do while an alarm is ringing. Pure logic, no Android types. */
object AlarmPolicy {

    data class Input(
        val mode: AlarmMode,
        val alarmPlaying: Boolean,
        val headphonesConnected: Boolean,
        /** The system already sends the alarm to the headphones by itself. */
        val alarmAlreadyOnHeadphones: Boolean,
        /** Whether an earbud is worn; null when that is not known. */
        val inEar: Boolean?,
    )

    data class Decision(
        /** Play a copy of the alarm into the headphones. */
        val mirror: Boolean,
        /** Lower the phone's own alarm volume for as long as the alarm rings. */
        val quietSpeaker: Boolean,
    )

    val NOTHING = Decision(mirror = false, quietSpeaker = false)

    fun decide(input: Input): Decision {
        if (input.mode == AlarmMode.OFF || !input.alarmPlaying || !input.headphonesConnected) return NOTHING

        // Only silence the speaker when an earbud is known to be worn. Headphones lying in an
        // open case are still "connected", and an alarm nobody can hear is the worst outcome.
        if (input.mode == AlarmMode.HEADPHONES_ONLY && input.inEar == true) {
            // Lowering the alarm volume also quietens any copy the system routes to the
            // headphones, so the mirror is always needed here.
            return Decision(mirror = true, quietSpeaker = true)
        }
        return Decision(mirror = !input.alarmAlreadyOnHeadphones, quietSpeaker = false)
    }
}
