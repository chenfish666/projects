package io.github.chenfish666.podspanel.core

/**
 * Apple/Beats audio devices, keyed by the two model bytes of the proximity pairing broadcast.
 *
 * [dual] devices report one battery level per earbud plus a case; the others report a single level.
 */
enum class PodModel(val code: Int, val label: String, val dual: Boolean = true) {
    AIRPODS_1(0x0220, "AirPods（第 1 代）"),
    AIRPODS_2(0x0F20, "AirPods（第 2 代）"),
    AIRPODS_3(0x1320, "AirPods（第 3 代）"),
    AIRPODS_4(0x1920, "AirPods 4"),
    AIRPODS_4_ANC(0x1B20, "AirPods 4（主動降噪）"),
    AIRPODS_5(0x3620, "AirPods 5"),
    AIRPODS_5_WIRELESS(0x3020, "AirPods 5（無線充電盒）"),
    AIRPODS_PRO(0x0E20, "AirPods Pro"),
    AIRPODS_PRO_2(0x1420, "AirPods Pro 2"),
    AIRPODS_PRO_2_USBC(0x2420, "AirPods Pro 2（USB-C）"),
    AIRPODS_PRO_3(0x2720, "AirPods Pro 3"),
    AIRPODS_MAX(0x0A20, "AirPods Max", dual = false),
    AIRPODS_MAX_USBC(0x1F20, "AirPods Max（USB-C）", dual = false),
    AIRPODS_MAX_2(0x2D20, "AirPods Max 2", dual = false),
    POWERBEATS_PRO(0x0B20, "Powerbeats Pro"),
    POWERBEATS_PRO_2(0x1D20, "Powerbeats Pro 2"),
    BEATS_FIT_PRO(0x1220, "Beats Fit Pro"),
    BEATS_STUDIO_BUDS(0x1120, "Beats Studio Buds"),
    BEATS_STUDIO_BUDS_PLUS(0x1620, "Beats Studio Buds+"),
    BEATS_SOLO_BUDS(0x2620, "Beats Solo Buds"),

    /** A model this app has no entry for. Treated as a pair of earbuds, which is the common case. */
    UNKNOWN(-1, "Apple 耳機");

    companion object {
        fun fromCode(code: Int): PodModel = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
