package com.example.openobd

/** Standard SAE J1979 (OBD-II) PIDs and response parsing. */
object Obd {
    class Pid(val code: String, val name: String, val unit: String, val decode: (IntArray) -> Double)

    val livePids = listOf(
        Pid("0C", "Engine RPM", "rpm") { (it[0] * 256 + it[1]) / 4.0 },
        Pid("0D", "Vehicle speed", "km/h") { it[0].toDouble() },
        Pid("05", "Coolant temp", "C") { it[0] - 40.0 },
        Pid("0F", "Intake air temp", "C") { it[0] - 40.0 },
        Pid("04", "Engine load", "%") { it[0] * 100.0 / 255 },
        Pid("11", "Throttle position", "%") { it[0] * 100.0 / 255 },
        Pid("2F", "Fuel level", "%") { it[0] * 100.0 / 255 },
    )

    /** Splits a raw adapter reply into lines; merges CAN multi-frame replies ("0:", "1:" ...). */
    fun normalize(raw: String): List<String> {
        val lines = raw.split('\r', '\n')
            .map { it.replace(" ", "").trim() }
            .filter { it.isNotEmpty() && it != ">" }
        if (lines.none { it.contains(':') }) return lines
        val joined = lines.filter { it.contains(':') }.joinToString("") { it.substringAfter(':') }
        return listOf(joined)
    }

    fun hexToBytes(hex: String): IntArray? {
        if (hex.length % 2 != 0) return null
        val out = IntArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
        }
        return out
    }

    /** Returns the data bytes after [prefix] (for example "410C") or null if no matching line. */
    fun payload(raw: String, prefix: String): IntArray? {
        val line = normalize(raw).firstOrNull { it.startsWith(prefix) } ?: return null
        return hexToBytes(line.removePrefix(prefix))
    }

    fun decodeDtcs(bytes: IntArray): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i + 1 < bytes.size) {
            val a = bytes[i]
            val b = bytes[i + 1]
            i += 2
            if (a == 0 && b == 0) continue
            val letter = "PCBU"[a shr 6]
            out += "%c%d%X%02X".format(letter, (a shr 4) and 3, a and 0xF, b)
        }
        return out
    }

    /** Mode 03 reply. CAN replies carry a count byte after 0x43; older protocols do not. */
    fun parseDtcs(raw: String, isCan: Boolean): List<String> {
        val codes = mutableListOf<String>()
        for (line in normalize(raw)) {
            if (!line.startsWith("43")) continue
            val bytes = hexToBytes(line.removePrefix("43")) ?: continue
            codes += decodeDtcs(if (isCan && bytes.isNotEmpty()) bytes.drop(1).toIntArray() else bytes)
        }
        return codes
    }

    /** Mode 09 PID 02 reply. */
    fun parseVin(raw: String): String? {
        val hex = normalize(raw).filter { it.startsWith("4902") }.joinToString("") { it.drop(6) }
        if (hex.length < 34) return null
        val bytes = hexToBytes(hex.takeLast(34)) ?: return null
        return bytes.map { it.toChar() }.filter { it.isLetterOrDigit() }.joinToString("")
    }
}
