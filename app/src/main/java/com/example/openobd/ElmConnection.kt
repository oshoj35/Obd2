package com.example.openobd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Talks to an ELM327-compatible Bluetooth Classic (SPP) adapter. */
class ElmConnection(private val device: BluetoothDevice) {
    private var socket: BluetoothSocket? = null
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private val lock = Mutex()

    var isCan = true
        private set
    var protocol = "6"
        private set

    private var inDiag = false
    private var diagReq = -1
    private var diagResp = -1

    @SuppressLint("MissingPermission")
    suspend fun connect() = withContext(Dispatchers.IO) {
        val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
        s.connect()
        socket = s
        input = s.inputStream
        output = s.outputStream
        for (cmd in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) send(cmd, 2500)
        send("0100", 10000) // triggers automatic protocol detection
        protocol = send("ATDPN").lines().lastOrNull().orEmpty().trim().removePrefix("A")
        isCan = protocol.firstOrNull()?.let { it in '6'..'9' } ?: true
    }

    suspend fun send(cmd: String, timeoutMs: Long = 3000): String = lock.withLock {
        withContext(Dispatchers.IO) {
            while (input.available() > 0) input.read()
            output.write((cmd + "\r").toByteArray())
            output.flush()
            val sb = StringBuilder()
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                if (input.available() > 0) {
                    val c = input.read().toChar()
                    if (c == '>') break
                    sb.append(c)
                } else {
                    delay(10)
                }
            }
            sb.toString().trim()
        }
    }

    /** Back to plain OBD-II addressing for live data and generic codes. */
    suspend fun enterObd() {
        if (!inDiag) return
        for (cmd in listOf("ATD", "ATE0", "ATL0", "ATS0", "ATH0")) send(cmd, 2000)
        inDiag = false
        diagReq = -1
        diagResp = -1
    }

    private suspend fun enterDiag() {
        if (inDiag) return
        if (protocol != "6") { send("ATSP6"); protocol = "6" } // 11-bit CAN, 500 kbit/s
        for (cmd in listOf("ATH0", "ATCAF1", "ATST32", "ATFCSM1", "ATFCSD300000")) send(cmd)
        inDiag = true
        diagReq = -1
        diagResp = -1
    }

    /** Sends one diagnostic request to a specific module and returns the raw reply. */
    suspend fun uds(req: Int, resp: Int, hex: String, timeoutMs: Long = 3000): String {
        enterDiag()
        if (req != diagReq || resp != diagResp) {
            send("ATSH%03X".format(req))
            send("ATFCSH%03X".format(req))
            send("ATCRA%03X".format(resp))
            diagReq = req
            diagResp = resp
        }
        return send(hex, timeoutMs)
    }

    /**
     * Probes every 11-bit ID in 0x700..0x7FF with Tester Present (3E 00). Any positive or negative
     * reply from an ID in the 0x7xx range marks a module. The reply ID is recorded as its response ID.
     */
    suspend fun scanModules(onProgress: (Int, Int, List<Module>) -> Unit): List<Module> {
        enterDiag()
        for (cmd in listOf("ATCRA", "ATCM700", "ATCF700", "ATH1")) send(cmd)
        val ids = (0x700..0x7FF).filter { it != 0x7DF && it !in 0x7E8..0x7EF }
        val found = mutableListOf<Module>()
        try {
            for ((n, req) in ids.withIndex()) {
                send("ATSH%03X".format(req))
                val r = send("3E00", 1200)
                for (line in Obd.normalize(r)) {
                    if (line.length < 7 || !line.all { it in '0'..'9' || it in 'A'..'F' }) continue
                    val id = line.take(3).toIntOrNull(16) ?: continue
                    val rest = line.drop(3)
                    val svc = listOf(rest, rest.drop(2)).any { it.startsWith("7E") || it.startsWith("7F") }
                    if (id in 0x700..0x7FF && svc && found.none { it.req == req }) {
                        found += Module(req, id)
                        break
                    }
                }
                onProgress(n + 1, ids.size, found.toList())
            }
        } finally {
            send("ATH0")
            send("ATCRA")
            diagReq = -1
            diagResp = -1
        }
        return found
    }

    fun close() {
        runCatching { socket?.close() }
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
