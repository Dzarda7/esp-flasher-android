package com.espflasher

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
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

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.connectedDevice == null) {
            DeviceList(state, vm)
            StatusAndLog(state, vm)
            return@Column
        }

        DeviceHeader(state, vm)
        PrimaryTabRow(selectedTabIndex = state.page.ordinal) {
            Tab(
                selected = state.page == Page.FLASH,
                onClick = { vm.selectPage(Page.FLASH) },
                text = { Text("Flash") },
            )
            Tab(
                selected = state.page == Page.DEVICE,
                onClick = { vm.selectPage(Page.DEVICE) },
                text = { Text("Device") },
            )
            Tab(
                selected = state.page == Page.MONITOR,
                onClick = { vm.selectPage(Page.MONITOR) },
                text = { Text(if (state.monitoring) "Monitor ●" else "Monitor") },
            )
        }
        when (state.page) {
            Page.FLASH -> FlashPage(state, vm)
            Page.DEVICE -> DevicePage(state, vm)
            Page.MONITOR -> MonitorPage(state, vm)
        }
    }
}

@Composable
private fun DeviceList(state: UiState, vm: FlasherViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("ESP Flasher", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
        TextButton(onClick = vm::refreshDevices) { Text("Refresh") }
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
                    Text("%04X:%04X".format(d.vendorId, d.productId), style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { vm.connect(driver) }, enabled = !state.busy) { Text("Connect") }
            }
        }
    }
}

@Composable
private fun DeviceHeader(state: UiState, vm: FlasherViewModel) {
    val info = state.chipInfo
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                if (info == null) "Connecting…" else info.chip + (info.flashSize?.let { " · ${it shr 20} MB" } ?: ""),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                listOfNotNull(info?.mac, state.connectedDevice).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
        OutlinedButton(onClick = vm::disconnect, enabled = !state.busy) { Text("Disconnect") }
    }
}

@Composable
private fun ColumnScope.FlashPage(state: UiState, vm: FlasherViewModel) {
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::setFirmware)
    }

    OutlinedButton(
        onClick = { pickFile.launch(arrayOf("*/*")) },
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(state.firmwareName ?: "Choose firmware .bin", maxLines = 1)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.offsetText,
            onValueChange = vm::setOffset,
            label = { Text("Offset") },
            singleLine = true,
            enabled = !state.busy,
            modifier = Modifier.weight(1f),
        )
        BaudRatePicker(state.baudRate, enabled = !state.busy, onSelect = vm::setBaudRate)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = state.monitorAfterFlash, onCheckedChange = vm::setMonitorAfterFlash)
        Text("Monitor after flash", Modifier.weight(1f))
        Button(
            onClick = vm::flash,
            enabled = !state.busy && state.chipInfo != null && state.firmwareUri != null,
        ) { Text("Flash") }
    }
    Progress(state)
    StatusAndLog(state, vm)
}

@Composable
private fun ColumnScope.DevicePage(state: UiState, vm: FlasherViewModel) {
    val info = state.chipInfo ?: return
    var confirmErase by remember { mutableStateOf(false) }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        uri -> uri?.let(vm::readFlash)
    }

    Column(Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
        InfoRow("Revision", info.revision ?: "unknown")
        Text("Security", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
        if (info.security == null) {
            Text("Not reported by this chip", style = MaterialTheme.typography.bodySmall)
        }
        info.security?.forEach { (name, on) -> InfoRow(name, if (on) "Yes" else "No", highlight = on) }
    }

    Text("Read flash to file", style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.readOffsetText,
            onValueChange = vm::setReadOffset,
            label = { Text("Offset") },
            singleLine = true,
            enabled = !state.busy,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = state.readSizeText,
            onValueChange = vm::setReadSize,
            label = { Text("Size") },
            singleLine = true,
            enabled = !state.busy,
            modifier = Modifier.weight(1f),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { saveFile.launch("${info.chip}_${info.mac.replace(":", "")}_${state.readOffsetText}.bin") },
            enabled = !state.busy,
        ) { Text("Read…") }
        Box(Modifier.weight(1f))
        OutlinedButton(onClick = { confirmErase = true }, enabled = !state.busy) {
            Text("Erase flash", color = MaterialTheme.colorScheme.error)
        }
    }
    Progress(state)
    StatusAndLog(state, vm)

    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            title = { Text("Erase flash?") },
            text = { Text("This erases the entire flash of the ${info.chip}, including the bootloader and all data.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmErase = false
                    vm.eraseFlash()
                }) { Text("Erase", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmErase = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun InfoRow(name: String, value: String, highlight: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (highlight) MaterialTheme.colorScheme.primary else Color.Unspecified,
        )
    }
}

/** Determinate while an operation reports progress, indeterminate while busy without it. */
@Composable
private fun Progress(state: UiState) {
    if (state.busy && state.progress == 0f) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
    }
}

/** Status line, with the detailed log behind a toggle. */
@Composable
private fun ColumnScope.StatusAndLog(state: UiState, vm: FlasherViewModel) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            state.status,
            Modifier.weight(1f),
            color = if (state.statusError) MaterialTheme.colorScheme.error else Color.Unspecified,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state.showLog) {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("ESP Flasher log", state.log.joinToString("\n")))
            }) { Text("Copy") }
        }
        if (state.log.isNotEmpty()) {
            TextButton(onClick = vm::toggleLog) { Text(if (state.showLog) "Hide" else "Details") }
        }
    }
    if (state.showLog) ConsoleLines(state.log)
}

@Composable
private fun ColumnScope.MonitorPage(state: UiState, vm: FlasherViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.monitoring) {
            OutlinedButton(onClick = vm::stopMonitor) { Text("Stop") }
            OutlinedButton(onClick = vm::startMonitor) { Text("Reset") }
        } else {
            Button(onClick = vm::startMonitor, enabled = !state.busy) { Text("Run & monitor") }
        }
        Box(Modifier.weight(1f))
        TextButton(onClick = vm::clearMonitor) { Text("Clear") }
    }
    ConsoleLines(state.monitorLines)

    if (state.monitoring) {
        var input by remember { mutableStateOf("") }
        val send = {
            vm.sendToMonitor(input)
            input = ""
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = { Text("Send to device") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            trailingIcon = { TextButton(onClick = send) { Text("Send") } },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BaudRatePicker(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) { Text("$selected baud") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FLASH_BAUD_RATES.forEach { baud ->
                DropdownMenuItem(
                    text = { Text("$baud") },
                    onClick = {
                        onSelect(baud)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Scrolling monospace text that fills the remaining height and follows new lines. */
@Composable
private fun ColumnScope.ConsoleLines(lines: List<String>) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
        items(lines) { line -> ConsoleLine(line) }
    }
}

private val ANSI_ESCAPE = Regex("\u001B\\[[0-9;]*m")
private val ANSI_RED = Color(0xFFE5534B)
private val ANSI_YELLOW = Color(0xFFD29922)
private val ANSI_GREEN = Color(0xFF3FB950)

/** ESP-IDF colors whole log lines (E red, W yellow, I green); keep that color, drop the escapes. */
@Composable
private fun ConsoleLine(raw: String) {
    val color = when {
        "\u001B[0;31m" in raw -> ANSI_RED
        "\u001B[0;33m" in raw -> ANSI_YELLOW
        "\u001B[0;32m" in raw -> ANSI_GREEN
        else -> Color.Unspecified
    }
    Text(ANSI_ESCAPE.replace(raw, ""), color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
}
