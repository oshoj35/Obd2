package com.example.openobd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

private fun hasBtPermission(ctx: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

@Composable
fun App(vm: ObdViewModel = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(bottomBar = {
        NavigationBar {
            listOf("Connect", "Live", "Codes", "Modules", "Console").forEachIndexed { i, t ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = {}, label = { Text(t) })
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            Text(vm.status, style = MaterialTheme.typography.titleMedium)
            vm.vin?.let { Text("VIN: $it") }
            Spacer(Modifier.height(12.dp))
            when (tab) {
                0 -> ConnectTab(vm)
                1 -> LiveTab(vm)
                2 -> CodesTab(vm)
                3 -> ModulesTab(vm)
                else -> ConsoleTab(vm)
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun ConnectTab(vm: ObdViewModel) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasBtPermission(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        Button(onClick = { launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text("Allow Bluetooth access") }
        return
    }
    val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    val paired = adapter?.bondedDevices?.toList().orEmpty()
    Text("Pair your ELM327 adapter in Android settings first, then pick it here.")
    LazyColumn {
        items(paired) { d ->
            val name = d.name ?: d.address
            ListItem(
                headlineContent = { Text(name) },
                supportingContent = { Text(d.address) },
                modifier = Modifier.clickable { vm.connect(d, name) },
            )
        }
    }
}

@Composable
fun LiveTab(vm: ObdViewModel) {
    Button(onClick = { vm.togglePolling() }) { Text(if (vm.polling) "Stop" else "Start") }
    LazyColumn {
        items(Obd.livePids) { pid ->
            ListItem(
                headlineContent = { Text(pid.name) },
                trailingContent = { Text(vm.live[pid.name] ?: "-") },
            )
        }
    }
}

@Composable
fun CodesTab(vm: ObdViewModel) {
    var confirm by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.readCodes() }) { Text("Read codes") }
        OutlinedButton(onClick = { confirm = true }) { Text("Clear codes") }
    }
    Spacer(Modifier.height(12.dp))
    val codes = vm.dtcs
    when {
        codes == null -> Text("Tap Read codes to scan.")
        codes.isEmpty() -> Text("No stored trouble codes.")
        else -> LazyColumn { items(codes) { ListItem(headlineContent = { Text(it) }) } }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Clear codes?") },
            text = { Text("This erases stored codes and resets readiness monitors. The car may fail an emissions test until it completes its drive cycles.") },
            confirmButton = { TextButton(onClick = { vm.clearCodes(); confirm = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
