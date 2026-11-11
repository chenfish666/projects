package io.github.chenfish666.podspanel.core

/**
 * The messages of Apple's accessory protocol (AAP) that this app uses.
 *
 * AAP is the channel an iPhone uses to talk to AirPods: a Bluetooth L2CAP connection on PSM
 * 0x1001, next to the ordinary audio connection. Unlike the public status broadcasts it carries
 * exact battery levels, reliable in-ear state and the noise control mode.
 *
 * Every message starts with `04 00 04 00`, followed by a two-byte opcode (little endian) and
 * the payload. Wire format as documented by the LibrePods and CAPod projects.
 */
object AapProtocol {

    const val PSM = 0x1001

    /** Opens the session. The AirPods answer nothing until they have received it. */
    val HANDSHAKE: ByteArray = bytes(
        0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )

    /** Asks the AirPods to push battery, in-ear and settings changes. Both variants are sent. */
    val ENABLE_NOTIFICATIONS: List<ByteArray> = listOf(
        bytes(0x04, 0x00, 0x04, 0x00, 0x0F, 0x00, 0xFF, 0xFF, 0xEF, 0xFF),
        bytes(0x04, 0x00, 0x04, 0x00, 0x0F, 0x00, 0xFF, 0xFF, 0xFF, 0xFF),
    )

    /** Unlocks newer features (adaptive mode among them) on recent models; ignored by older ones. */
    val ENABLE_FEATURES: ByteArray = bytes(
        0x04, 0x00, 0x04, 0x00, 0x4D, 0x00, 0xD7, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )

    enum class Side { LEFT, RIGHT }

    enum class Placement { IN_EAR, OUT, IN_CASE, UNKNOWN }

    enum class ListeningMode(val wire: Int, val label: String) {
        OFF(0x01, "關閉"),
        NOISE_CANCELLATION(0x02, "降噪"),
        TRANSPARENCY(0x03, "通透"),
        ADAPTIVE(0x04, "自適應");

        companion object {
            fun fromWire(wire: Int): ListeningMode? = entries.firstOrNull { it.wire == wire }
        }
    }

    data class Level(val percent: Int, val charging: Boolean)

    sealed interface Event {
        /** Levels of the parts that reported one. [first] is the earbud listed first, the primary one. */
        data class Battery(
            val left: Level?, val right: Level?, val case: Level?, val single: Level?, val first: Side?,
        ) : Event

        /** Where the primary and the secondary earbud are. Which is left is told by [Role]. */
        data class Ear(val primary: Placement, val secondary: Placement) : Event

        data class Role(val primary: Side) : Event

        data class Mode(val mode: ListeningMode) : Event

        /** The answer to the handshake; status 0 means the session is open. */
        data class Connected(val status: Int) : Event
    }

    fun setListeningMode(mode: ListeningMode): ByteArray =
        bytes(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, CONTROL_LISTENING_MODE, mode.wire, 0x00, 0x00, 0x00)

    /** Decodes one received frame; null for the many message kinds this app has no use for. */
    fun parse(frame: ByteArray): Event? {
        if (frame.size < 4) return null
        fun u(i: Int) = frame[i].toInt() and 0xFF

        val packetType = u(0) or (u(1) shl 8)
        if (packetType == PACKET_CONNECT_RESPONSE) {
            return Event.Connected(status = if (frame.size >= 6) u(4) or (u(5) shl 8) else 0)
        }
        if (packetType != PACKET_MESSAGE || frame.size < 6) return null

        val opcode = u(4) or (u(5) shl 8)
        val payload = frame.copyOfRange(6, frame.size)
        fun p(i: Int) = payload[i].toInt() and 0xFF

        return when (opcode) {
            OP_BATTERY -> parseBattery(payload)
            OP_EAR -> if (payload.size < 2) null else Event.Ear(placement(p(0)), placement(p(1)))
            OP_ROLE -> when (payload.firstOrNull()?.toInt()) {
                0x01 -> Event.Role(Side.LEFT)
                0x02 -> Event.Role(Side.RIGHT)
                else -> null
            }
            OP_CONTROL ->
                if (payload.size >= 2 && p(0) == CONTROL_LISTENING_MODE) {
                    ListeningMode.fromWire(p(1))?.let { Event.Mode(it) }
                } else {
                    null
                }
            else -> null
        }
    }

    private fun parseBattery(payload: ByteArray): Event.Battery? {
        if (payload.isEmpty()) return null
        val count = payload[0].toInt() and 0xFF
        var left: Level? = null
        var right: Level? = null
        var case: Level? = null
        var single: Level? = null
        var first: Side? = null

        // Each entry: part, spacer, percent, charging state, spacer.
        for (i in 0 until count) {
            val offset = 1 + i * 5
            if (offset + 5 > payload.size) break
            val part = payload[offset].toInt() and 0xFF
            val percent = payload[offset + 2].toInt() and 0xFF
            val state = payload[offset + 3].toInt() and 0xFF
            if (first == null) {
                first = when (part) {
                    PART_LEFT -> Side.LEFT
                    PART_RIGHT -> Side.RIGHT
                    else -> null
                }
            }
            // Above 100 is a placeholder (127 after the case closes, 255 when a part is absent).
            if (percent > 100 || state == STATE_DISCONNECTED) continue
            val level = Level(percent, charging = state == STATE_CHARGING || state == STATE_CHARGING_OPTIMIZED)
            when (part) {
                PART_LEFT -> left = level
                PART_RIGHT -> right = level
                PART_CASE -> case = level
                PART_SINGLE -> single = level
            }
        }
        return Event.Battery(left, right, case, single, first)
    }

    private fun placement(wire: Int) = when (wire) {
        0x00 -> Placement.IN_EAR
        0x01 -> Placement.OUT
        0x02 -> Placement.IN_CASE
        else -> Placement.UNKNOWN
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private const val PACKET_CONNECT_RESPONSE = 0x0001
    private const val PACKET_MESSAGE = 0x0004
    private const val OP_BATTERY = 0x0004
    private const val OP_EAR = 0x0006
    private const val OP_ROLE = 0x0008
    private const val OP_CONTROL = 0x0009
    private const val CONTROL_LISTENING_MODE = 0x0D
    private const val PART_SINGLE = 0x01
    private const val PART_RIGHT = 0x02
    private const val PART_LEFT = 0x04
    private const val PART_CASE = 0x08
    private const val STATE_CHARGING = 0x01
    private const val STATE_DISCONNECTED = 0x04
    private const val STATE_CHARGING_OPTIMIZED = 0x05
}

/**
 * Folds the stream of AAP events into the current picture of the AirPods.
 *
 * The events are partial: a battery message may omit the case, and the in-ear message speaks of
 * a "primary" and "secondary" earbud whose sides are announced separately. This class remembers
 * the pieces and produces a [PodsStatus] once at least one level is known.
 */
class AapState {

    private var left: AapProtocol.Level? = null
    private var right: AapProtocol.Level? = null
    private var case: AapProtocol.Level? = null
    private var single: AapProtocol.Level? = null
    private var primary: AapProtocol.Side? = null
    private var primaryPlacement = AapProtocol.Placement.UNKNOWN
    private var secondaryPlacement = AapProtocol.Placement.UNKNOWN

    var mode: AapProtocol.ListeningMode? = null
        private set

    /** Number of earbuds in an ear, or null before the AirPods have reported it. */
    val wornCount: Int?
        get() {
            if (primaryPlacement == AapProtocol.Placement.UNKNOWN && secondaryPlacement == AapProtocol.Placement.UNKNOWN) return null
            return listOf(primaryPlacement, secondaryPlacement).count { it == AapProtocol.Placement.IN_EAR }
        }

    fun apply(event: AapProtocol.Event) {
        when (event) {
            is AapProtocol.Event.Battery -> {
                // An earbud missing from the message has dropped out; the case merely stops
                // reporting once both earbuds have left it, so its last level is kept.
                left = event.left
                right = event.right
                single = event.single
                if (event.case != null) case = event.case
                if (primary == null) primary = event.first
            }
            is AapProtocol.Event.Ear -> {
                primaryPlacement = event.primary
                secondaryPlacement = event.secondary
            }
            is AapProtocol.Event.Role -> primary = event.primary
            is AapProtocol.Event.Mode -> mode = event.mode
            is AapProtocol.Event.Connected -> Unit
        }
    }

    /**
     * @param modelCode model learned from a status broadcast, or -1 when none was seen
     * @param lid lid state from the broadcasts; AAP does not report it
     */
    fun toStatus(modelCode: Int, lid: LidState): PodsStatus? {
        if (left == null && right == null && single == null) return null
        val leftIsPrimary = primary != AapProtocol.Side.RIGHT
        val leftPlacement = if (leftIsPrimary) primaryPlacement else secondaryPlacement
        val rightPlacement = if (leftIsPrimary) secondaryPlacement else primaryPlacement
        return PodsStatus(
            modelCode = modelCode,
            left = left?.percent ?: single?.percent,
            right = right?.percent,
            case = case?.percent,
            leftCharging = (left ?: single)?.charging == true,
            rightCharging = right?.charging == true,
            caseCharging = case?.charging == true,
            leftInEar = leftPlacement == AapProtocol.Placement.IN_EAR,
            rightInEar = rightPlacement == AapProtocol.Placement.IN_EAR,
            lid = lid,
            connection = ConnectionState.IDLE,
            colorCode = 0,
        )
    }

    fun reset() {
        left = null; right = null; case = null; single = null
        primary = null
        primaryPlacement = AapProtocol.Placement.UNKNOWN
        secondaryPlacement = AapProtocol.Placement.UNKNOWN
        mode = null
    }
}
