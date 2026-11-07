package com.example.openobd

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ModulesTab(vm: ObdViewModel) {
    val sel = vm.selected
    if (sel == null) {
        Text("Ignition ON, engine off. Scanning probes IDs 0x700 to 0x7FF and takes about a minute.")
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.scanModules() }, enabled = !vm.busy) { Text("Scan for modules") }
        Text(vm.scanStatus)
        LazyColumn {
            items(vm.modules) { m ->
                ListItem(
                    headlineContent = { Text(m.name) },
                    supportingContent = { Text("request 0x%03X, response 0x%03X".format(m.req, m.resp)) },
                    modifier = Modifier.clickable { vm.moduleLog.clear(); vm.selected = m },
                )
            }
        }
        return
    }
    var confirm by remember { mutableStateOf(false) }
    Text(sel.name + "  (0x%03X / 0x%03X)".format(sel.req, sel.resp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.readModuleDtcs() }, enabled = !vm.busy) { Text("Read DTCs") }
        OutlinedButton(onClick = { vm.identifyModule() }, enabled = !vm.busy) { Text("Identify") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { confirm = true }, enabled = !vm.busy) { Text("Clear DTCs") }
        TextButton(onClick = { vm.selected = null }) { Text("Back") }
    }
    Spacer(Modifier.height(8.dp))
    SelectionContainer {
        Text(vm.moduleLog.joinToString("\n"), modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Clear DTCs on ${sel.name}?") },
            text = { Text("Read and note the codes first. Clearing erases freeze-frame data and may reset readiness monitors.") },
            confirmButton = { TextButton(onClick = { vm.clearModuleDtcs(); confirm = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ConsoleTab(vm: ObdViewModel) {
    var req by remember { mutableStateOf("7E0") }
    var resp by remember { mutableStateOf("7E8") }
    var cmd by remember { mutableStateOf("1902FF") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Raw diagnostic request. Read-style services only (${Uds.SAFE_SERVICES.sorted().joinToString(" ")}).")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(req, { req = it }, label = { Text("Request ID") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(resp, { resp = it }, label = { Text("Response ID") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(cmd, { cmd = it }, label = { Text("Command (hex)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.sendRaw(req, resp, cmd) }, enabled = !vm.busy) { Text("Send") }
        SelectionContainer { Text(vm.consoleOut) }
    }
}
