package com.espflasher

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val vm: FlasherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    FlasherScreen(vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) vm.refreshDevices()
    }

    override fun onResume() {
        super.onResume()
        vm.refreshDevices()
    }
}

@Composable
fun FlasherScreen(vm: FlasherViewModel) {
    val state by vm.state.collectAsState()
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::setFirmware)
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ESP Flasher", style = MaterialTheme.typography.headlineSmall)

        // Device selection / connection
        if (state.connectedDevice == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("USB devices", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = vm::refreshDevices) { Text("Refresh") }
            }
            if (state.devices.isEmpty()) {
                Text("No USB-serial device found. Plug in a board via OTG.")
            }
            state.devices.forEach { driver ->
                val d = driver.device
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.productName ?: driver.javaClass.simpleName)
                            Text(
                                "%04X:%04X".format(d.vendorId, d.productId),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(onClick = { vm.connect(driver) }, enabled = !state.busy) { Text("Connect") }
                    }
                }
            }
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.connectedDevice!!, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = vm::disconnect, enabled = !state.busy) { Text("Disconnect") }
                    }
                    state.chipInfo?.let { info ->
                        Text("Chip: ${info.chip}")
                        Text("MAC: ${info.mac}")
                        info.flashSize?.let { Text("Flash: ${it / (1024 * 1024)} MB") }
                    }
                }
            }

            // Flashing
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickFile.launch(arrayOf("*/*")) }, enabled = !state.busy) {
                    Text("Choose .bin")
                }
                Text(state.firmwareName ?: "No file", Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.offsetText,
                    onValueChange = vm::setOffset,
                    label = { Text("Offset") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = vm::flash,
                    enabled = !state.busy && state.chipInfo != null && state.firmwareUri != null,
                ) { Text("Flash") }
            }
            state.progress?.let { p -> LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) }
        }

        // Log
        Text("Log", style = MaterialTheme.typography.titleMedium)
        val listState = rememberLazyListState()
        LaunchedEffect(state.log.size) {
            if (state.log.isNotEmpty()) listState.animateScrollToItem(state.log.lastIndex)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
            items(state.log) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}
