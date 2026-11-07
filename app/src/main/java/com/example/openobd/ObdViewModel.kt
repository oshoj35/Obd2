package com.example.openobd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ObdViewModel : ViewModel() {
    var status by mutableStateOf("Not connected"); private set
    var vin by mutableStateOf<String?>(null); private set
    var dtcs by mutableStateOf<List<String>?>(null); private set
    var polling by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var modules by mutableStateOf<List<Module>>(emptyList()); private set
    var scanStatus by mutableStateOf("Not scanned"); private set
    var selected by mutableStateOf<Module?>(null)
    var consoleOut by mutableStateOf("")
    val live = mutableStateMapOf<String, String>()
    val moduleLog = mutableStateListOf<String>()

    private var conn: ElmConnection? = null
    private var pollJob: Job? = null

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, label: String) {
        viewModelScope.launch {
            stopPolling()
            conn?.close()
            conn = null
            modules = emptyList()
            selected = null
            status = "Connecting to $label..."
            try {
                val c = ElmConnection(device)
                c.connect()
                conn = c
                status = "Connected to $label"
                vin = Obd.parseVin(c.send("0902", 6000))
            } catch (e: Exception) {
                status = "Failed: ${e.message}"
            }
        }
    }

    // ---- Generic OBD-II ----

    fun togglePolling() {
        if (polling) { stopPolling(); return }
        val c = conn ?: return
        polling = true
        pollJob = viewModelScope.launch {
            try {
                c.enterObd()
                while (isActive) {
                    for (pid in Obd.livePids) {
                        val data = Obd.payload(c.send("01" + pid.code), "41" + pid.code)
                        live[pid.name] = data
                            ?.let { runCatching { "%.0f %s".format(pid.decode(it), pid.unit) }.getOrNull() }
                            ?: "n/a"
                    }
                }
            } catch (e: Exception) {
                status = "Connection lost: ${e.message}"
            } finally {
                polling = false
            }
        }
    }

    fun readCodes() {
        val c = conn ?: return
        stopPolling()
        viewModelScope.launch {
            c.enterObd()
            dtcs = Obd.parseDtcs(c.send("03", 6000), c.isCan)
        }
    }

    fun clearCodes() {
        val c = conn ?: return
        stopPolling()
        viewModelScope.launch {
            c.enterObd()
            c.send("04", 6000)
            dtcs = null
        }
    }

    // ---- Module scan and per-module diagnostics ----

    fun scanModules() {
        val c = conn ?: return
        if (busy) return
        stopPolling()
        viewModelScope.launch {
            busy = true
            modules = emptyList()
            selected = null
            try {
                modules = c.scanModules { n, total, found ->
                    scanStatus = "Probing $n/$total, found ${found.size}"
                    modules = found
                }
                scanStatus = "Done: ${modules.size} module(s) responded"
            } catch (e: Exception) {
                scanStatus = "Scan failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    private fun moduleOp(block: suspend (ElmConnection, Module) -> Unit) {
        val c = conn ?: return
        val m = selected ?: return
        if (busy) return
        stopPolling()
        viewModelScope.launch {
            busy = true
            moduleLog.clear()
            try {
                block(c, m)
            } catch (e: Exception) {
                moduleLog += "Error: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun readModuleDtcs() = moduleOp { c, m ->
        val r19 = c.uds(m.req, m.resp, "1902FF", 6000)
        moduleLog += "19 02 FF -> ${Uds.compact(r19)}"
        val a = Uds.parse19(r19)
        if (a != null) {
            moduleLog += if (a.isEmpty()) "No stored DTCs (UDS)" else a.joinToString("\n")
            return@moduleOp
        }
        val r18 = c.uds(m.req, m.resp, "1800FF00", 6000)
        moduleLog += "18 00 FF 00 -> ${Uds.compact(r18)}"
        val b = Uds.parse18(r18)
        moduleLog += when {
            b == null -> "Module did not answer a DTC request in a format this app understands."
            b.isEmpty() -> "No stored DTCs (KWP)"
            else -> b.joinToString("\n")
        }
    }

    fun clearModuleDtcs() = moduleOp { c, m ->
        val r = c.uds(m.req, m.resp, "14FFFFFF", 6000)
        if (Uds.isPositive(r, "54")) {
            moduleLog += "Cleared (UDS)"
            return@moduleOp
        }
        val k = c.uds(m.req, m.resp, "14FF00", 6000)
        moduleLog += if (Uds.isPositive(k, "54")) "Cleared (KWP)" else "Module refused: ${Uds.compact(r)} / ${Uds.compact(k)}"
    }

    fun identifyModule() = moduleOp { c, m ->
        for (did in listOf("F187", "F189", "F18C", "F190")) {
            val r = c.uds(m.req, m.resp, "22$did", 3000)
            val line = Obd.normalize(r).firstOrNull { it.startsWith("62$did") }
            if (line != null) moduleLog += "$did: " + Uds.ascii(Obd.hexToBytes(line.drop(6)) ?: IntArray(0))
        }
        val k = c.uds(m.req, m.resp, "1A87", 3000)
        Obd.normalize(k).firstOrNull { it.startsWith("5A87") }?.let {
            moduleLog += "KWP 1A87: " + Uds.ascii(Obd.hexToBytes(it.drop(4)) ?: IntArray(0))
        }
        if (moduleLog.isEmpty()) moduleLog += "No identification returned. Last raw reply: ${Uds.compact(k)}"
    }

    // ---- Raw console (read-style services only) ----

    fun sendRaw(reqHex: String, respHex: String, cmd: String) {
        val c = conn ?: run { consoleOut = "Not connected"; return }
        val req = reqHex.trim().toIntOrNull(16)
        val resp = respHex.trim().toIntOrNull(16)
        val hex = cmd.replace(" ", "").uppercase()
        if (req == null || resp == null || hex.length < 2 || hex.length % 2 != 0) {
            consoleOut = "Check the IDs (hex) and the command (even number of hex digits)."
            return
        }
        if (hex.take(2) !in Uds.SAFE_SERVICES) {
            consoleOut = "Service ${hex.take(2)} is blocked. Allowed: ${Uds.SAFE_SERVICES.sorted().joinToString(" ")}"
            return
        }
        if (busy) return
        stopPolling()
        viewModelScope.launch {
            busy = true
            try {
                consoleOut = Uds.compact(c.uds(req, resp, hex, 5000))
            } catch (e: Exception) {
                consoleOut = "Error: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        polling = false
    }

    override fun onCleared() {
        pollJob?.cancel()
        conn?.close()
    }
}
