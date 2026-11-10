package io.github.chenfish666.podspanel.core

/**
 * Decodes the plaintext status that AirPods broadcast over Bluetooth LE ("proximity pairing").
 *
 * The broadcast is Apple manufacturer data (company 0x004C) holding type/length/value records.
 * Record type 0x07 with length 25 is the status record:
 *
 * ```
 * offset  meaning
 *   0     prefix, 0x01 for status broadcasts
 *   1-2   model
 *   3     status bits: 1 and 3 in-ear, 2 both in case, 4 one in case, 5 primary is left, 6 sender in case
 *   4     earbud battery, one nibble each, in tenths (15 = unknown)
 *   5     upper nibble charging flags, lower nibble case battery in tenths
 *   6     lid; bit 3 set means closed
 *   7     colour
 *   8     connection state
 *   9-24  encrypted, needs keys only a paired Apple device has
 * ```
 *
 * Because only the plaintext part is readable, battery levels come in steps of 10%.
 * Layout as documented by the CAPod and OpenPods projects.
 */
object ProximityParser {

    const val APPLE_COMPANY_ID = 0x004C
    const val TYPE_PROXIMITY_PAIRING = 0x07
    const val STATUS_LENGTH = 25
    private const val STATUS_PREFIX = 0x01
    private const val READABLE_BYTES = 9

    /** @param manufacturerData Apple manufacturer data without the two company ID bytes. */
    fun parse(manufacturerData: ByteArray?): PodsStatus? {
        val record = findStatusRecord(manufacturerData ?: return null) ?: return null
        fun u(i: Int) = record[i].toInt() and 0xFF

        if (u(0) != STATUS_PREFIX) return null

        val modelCode = (u(1) shl 8) or u(2)
        val status = u(3)
        val podsBattery = u(4)
        val flags = u(5) shr 4
        val caseBattery = u(5) and 0x0F
        val lidRaw = u(6)
        fun bit(value: Int, n: Int) = (value shr n) and 1 == 1

        val senderInCase = bit(status, 6)
        val oneInCase = bit(status, 4)
        val bothInCase = bit(status, 2)

        // The lower nibbles describe the primary earbud. They are the left one's only when the
        // left earbud is primary; otherwise left and right swap places.
        val flipped = !bit(status, 5)

        val lowNibble = podsBattery and 0x0F
        val highNibble = podsBattery shr 4

        val dual = PodModel.fromCode(modelCode).dual
        val left: Int?
        val right: Int?
        val leftCharging: Boolean
        val rightCharging: Boolean
        val leftInEar: Boolean
        val rightInEar: Boolean
        if (dual) {
            left = tenths(if (flipped) highNibble else lowNibble)
            right = tenths(if (flipped) lowNibble else highNibble)
            leftCharging = bit(flags, if (flipped) 1 else 0)
            rightCharging = bit(flags, if (flipped) 0 else 1)
            // The in-ear bits swap once more when the sending earbud is the one in the case.
            val earFlipped = flipped xor senderInCase
            leftInEar = bit(status, if (earFlipped) 3 else 1)
            rightInEar = bit(status, if (earFlipped) 1 else 3)
        } else {
            left = tenths(lowNibble)
            right = null
            leftCharging = bit(flags, 0)
            rightCharging = false
            leftInEar = bit(status, 1)
            rightInEar = leftInEar
        }

        val lid = when {
            !dual -> LidState.NOT_IN_CASE
            !(senderInCase || oneInCase || bothInCase) -> LidState.NOT_IN_CASE
            // An earbud outside the case repeats a stale lid byte, so only trust the lid when
            // the sender is in the case or both earbuds are.
            !(senderInCase || bothInCase) -> LidState.UNKNOWN
            bit(lidRaw, 3) -> LidState.CLOSED
            else -> LidState.OPEN
        }

        return PodsStatus(
            modelCode = modelCode,
            left = left,
            right = right,
            case = if (dual) tenths(caseBattery) else null,
            leftCharging = leftCharging,
            rightCharging = rightCharging,
            caseCharging = dual && bit(flags, 2),
            leftInEar = leftInEar,
            rightInEar = rightInEar,
            lid = lid,
            connection = ConnectionState.fromRaw(u(8)),
            colorCode = u(7),
        )
    }

    /** Walks the type/length/value records and returns the body of the status record. */
    private fun findStatusRecord(data: ByteArray): ByteArray? {
        var pos = 0
        while (pos + 2 <= data.size) {
            val type = data[pos].toInt() and 0xFF
            val length = data[pos + 1].toInt() and 0xFF
            val start = pos + 2
            val end = start + length
            if (end > data.size) return null
            if (type == TYPE_PROXIMITY_PAIRING && length == STATUS_LENGTH && length >= READABLE_BYTES) {
                return data.copyOfRange(start, end)
            }
            pos = end
        }
        return null
    }

    private fun tenths(nibble: Int): Int? = when {
        nibble == 15 -> null
        nibble > 10 -> 100
        else -> nibble * 10
    }
}
