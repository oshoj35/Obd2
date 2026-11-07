package com.example.openobd

data class Module(val req: Int, val resp: Int) {
    val name: String
        get() = when (req) {
            0x7E0 -> "Engine (ECM)"
            0x7E1 -> "Transmission (TCM)"
            else -> "Module %03X".format(req)
        }
}

/** ISO 14229 (UDS) and KWP2000-on-CAN response parsing. */
object Uds {
    /** Services the raw console will send. Read-style only. Edit this set at your own risk. */
    val SAFE_SERVICES = setOf("01", "02", "09", "0A", "10", "18", "19", "1A", "22", "3E")

    fun compact(raw: String): String =
        Obd.normalize(raw).joinToString(" | ").ifEmpty { raw.ifBlank { "(no reply)" } }

    fun isNegative(raw: String) = Obd.normalize(raw).any { it.startsWith("7F") }

    fun isPositive(raw: String, sid: String) = Obd.normalize(raw).any { it.startsWith(sid) }

    fun ascii(bytes: IntArray): String =
        bytes.map { it.toChar() }.filter { it in ' '..'~' }.joinToString("").trim()

    private fun fmt(hi: Int, lo: Int, extra: String): String? {
        val code = Obd.decodeDtcs(intArrayOf(hi, lo)).firstOrNull() ?: return null
        return "$code  $extra"
    }

    /** UDS 0x19 02: reply 59 02 <mask> then 4 bytes per DTC (3 DTC bytes + status). Null if not a 59 02 reply. */
    fun parse19(raw: String): List<String>? {
        val line = Obd.normalize(raw).firstOrNull { it.startsWith("5902") } ?: return null
        val b = Obd.hexToBytes(line.removePrefix("5902")) ?: return null
        val out = mutableListOf<String>()
        var i = 1
        while (i + 3 < b.size) {
            fmt(b[i], b[i + 1], "FTB %02X status %02X".format(b[i + 2], b[i + 3]))?.let { out += it }
            i += 4
        }
        return out
    }

    /** KWP 0x18: reply 58 <count> then 3 bytes per DTC (2 DTC bytes + status). Null if not a 58 reply. */
    fun parse18(raw: String): List<String>? {
        val line = Obd.normalize(raw).firstOrNull { it.startsWith("58") } ?: return null
        val b = Obd.hexToBytes(line.removePrefix("58")) ?: return null
        val out = mutableListOf<String>()
        var i = 1
        while (i + 2 < b.size) {
            fmt(b[i], b[i + 1], "status %02X".format(b[i + 2]))?.let { out += it }
            i += 3
        }
        return out
    }
}
