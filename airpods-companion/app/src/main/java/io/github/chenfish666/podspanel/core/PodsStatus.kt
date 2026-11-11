package io.github.chenfish666.podspanel.core

enum class LidState { OPEN, CLOSED, NOT_IN_CASE, UNKNOWN }

enum class ConnectionState(val raw: Int, val label: String) {
    DISCONNECTED(0x00, "未連線"),
    IDLE(0x04, "已連線"),
    MUSIC(0x05, "播放中"),
    CALL(0x06, "通話中"),
    RINGING(0x07, "來電中"),
    HANGING_UP(0x09, "掛斷中"),
    UNKNOWN(-1, "");

    companion object {
        fun fromRaw(raw: Int): ConnectionState = entries.firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

/**
 * One decoded status broadcast. Battery levels are percentages, null when the device did not
 * report one (the case level is only sent while an earbud sits in the case).
 */
data class PodsStatus(
    val modelCode: Int,
    val left: Int?,
    val right: Int?,
    val case: Int?,
    val leftCharging: Boolean,
    val rightCharging: Boolean,
    val caseCharging: Boolean,
    val leftInEar: Boolean,
    val rightInEar: Boolean,
    val lid: LidState,
    val connection: ConnectionState,
    val colorCode: Int,
) {
    val model: PodModel get() = PodModel.fromCode(modelCode)

    /** For single-level devices such as AirPods Max, [left] carries the headset level. */
    val single: Int? get() = if (model.dual) null else left

    val anyInEar: Boolean get() = leftInEar || rightInEar
    val bothInEar: Boolean get() = leftInEar && rightInEar

    /** Lowest earbud level, for the "battery low" tint. */
    val lowestPod: Int? get() = listOfNotNull(left, right.takeIf { model.dual }).minOrNull()

    /** The lasting part of the status (model, levels, charging) as text, to store between runs. */
    fun encodeLevels(): String =
        listOf(modelCode, left, right, case, leftCharging, rightCharging, caseCharging).joinToString(",") { it?.toString() ?: "" }

    companion object {
        /** Reverse of [encodeLevels]; null when the text is not a stored status. */
        fun decodeLevels(text: String?): PodsStatus? {
            val parts = text?.split(',') ?: return null
            if (parts.size != 7) return null
            return PodsStatus(
                modelCode = parts[0].toIntOrNull() ?: return null,
                left = parts[1].toIntOrNull(),
                right = parts[2].toIntOrNull(),
                case = parts[3].toIntOrNull(),
                leftCharging = parts[4] == "true",
                rightCharging = parts[5] == "true",
                caseCharging = parts[6] == "true",
                leftInEar = false,
                rightInEar = false,
                lid = LidState.UNKNOWN,
                connection = ConnectionState.UNKNOWN,
                colorCode = 0,
            )
        }
    }
}
