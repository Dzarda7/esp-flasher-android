package io.github.dzarda7.espflasher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
            val dark = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
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
            if (!state.showLog) Box(Modifier.weight(1f))
            Text(
                "Powered by esp-serial-flasher ${BuildConfig.FLASHER_VERSION}",
                Modifier.align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        DeviceHeader(state, vm)
        // Tabs and swiping both drive state.page; the pager follows it (also when the
        // ViewModel switches page itself, e.g. to Monitor after flashing).
        val pager = rememberPagerState(initialPage = state.page.ordinal) { Page.entries.size }
        LaunchedEffect(state.page) { pager.animateScrollToPage(state.page.ordinal) }
        LaunchedEffect(pager.settledPage) { vm.selectPage(Page.entries[pager.settledPage]) }

        // The tab row follows the pager directly (switches at half a swipe) instead of waiting
        // for state.page, which only changes once the swipe settles.
        PrimaryTabRow(selectedTabIndex = pager.currentPage) {
            Page.entries.forEach { page ->
                val label = when (page) {
                    Page.FLASH -> "Flash"
                    Page.DEVICE -> "Device"
                    Page.EFUSE -> "eFuse"
                    Page.MONITOR -> if (state.monitoring) "Monitor ●" else "Monitor"
                }
                Tab(selected = pager.currentPage == page.ordinal, onClick = { vm.selectPage(page) }, text = { Text(label) })
            }
        }
        HorizontalPager(pager, Modifier.weight(1f), verticalAlignment = Alignment.Top) { index ->
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (Page.entries[index]) {
                    Page.FLASH -> FlashPage(state, vm)
                    Page.DEVICE -> DevicePage(state, vm)
                    Page.EFUSE -> EfusePage(state, vm)
                    Page.MONITOR -> MonitorPage(state, vm)
                }
            }
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
        Column(
            Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppLogo(96.dp)
            Text("Plug in an ESP board", style = MaterialTheme.typography.titleMedium)
            Text(
                "Use a USB-OTG cable. Boards with a USB-UART bridge and chips with built-in USB both work.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
    state.devices.forEach { driver ->
        val d = driver.device
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.productName ?: driver.javaClass.simpleName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${adapterKind(d.vendorId)} · %04X:%04X".format(d.vendorId, d.productId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = { vm.connect(driver) }, enabled = !state.busy) { Text("Connect") }
            }
        }
    }
}

private fun adapterKind(vendorId: Int) = when (vendorId) {
    UsbSerialBridge.ESPRESSIF_VID -> "Built-in USB"
    0x10C4 -> "CP210x"
    0x1A86 -> "CH34x"
    0x0403 -> "FTDI"
    else -> "USB-UART"
}

/** The launcher icon, drawn in the UI (adaptive-icon mipmaps can't be painted directly). */
@Composable
private fun AppLogo(size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Color(0xFFD9412F)), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, Modifier.fillMaxSize())
    }
}

@Composable
private fun DeviceHeader(state: UiState, vm: FlasherViewModel) {
    val info = state.chipInfo
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(info?.chip ?: "Connecting…", style = MaterialTheme.typography.titleLarge)
                if (info != null) {
                    Text(
                        listOfNotNull(
                            info.revision?.let { "rev v${it / 100}.${it % 100}" },
                            info.flashSize?.let { "${it shr 20} MB flash" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    listOfNotNull(info?.mac, state.connectedDevice).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            TextButton(onClick = vm::disconnect, enabled = !state.busy) { Text("Disconnect") }
        }
    }
}

@Composable
private fun ColumnScope.FlashPage(state: UiState, vm: FlasherViewModel) {
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addImages(uris)
    }

    state.images.forEachIndexed { i, img ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(img.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            OutlinedTextField(
                value = img.offsetText,
                onValueChange = { vm.setImageOffset(i, it) },
                singleLine = true,
                enabled = !state.busy,
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.width(120.dp),
            )
            TextButton(onClick = { vm.removeImage(i) }, enabled = !state.busy) { Text("✕") }
        }
    }
    OutlinedButton(
        onClick = { pickFiles.launch(arrayOf("*/*")) },
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (state.images.isEmpty()) "+ Add binaries" else "+ Add more")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = state.monitorAfterFlash, onCheckedChange = vm::setMonitorAfterFlash)
        Text("Monitor after flash", Modifier.weight(1f))
        BaudRatePicker(state.baudRate, enabled = !state.busy, onSelect = vm::setBaudRate)
    }
    Button(
        onClick = vm::flash,
        enabled = !state.busy && state.chipInfo != null && state.images.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (state.images.size > 1) "Flash ${state.images.size} files" else "Flash")
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
        InfoRow("Revision", info.revision?.let { "v${it / 100}.${it % 100}" } ?: "unknown")
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
private fun ColumnScope.EfusePage(state: UiState, vm: FlasherViewModel) {
    LaunchedEffect(state.page) {
        if (state.page == Page.EFUSE && state.efuses == null && !state.busy) vm.readEfuses()
    }
    val efuses = state.efuses
    if (efuses == null) {
        Progress(state)
        StatusAndLog(state, vm)
        return
    }

    val context = LocalContext.current
    var selected by remember { mutableStateOf<EfuseField?>(null) }
    val filter = state.efuseFilter.trim()
    val shown = efuses.filter {
        (filter.isEmpty() || it.name.contains(filter, ignoreCase = true)) && (!state.efuseSetOnly || !it.isZero)
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.efuseFilter,
            onValueChange = vm::setEfuseFilter,
            placeholder = { Text("Search") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        FilterChip(selected = state.efuseSetOnly, onClick = vm::toggleEfuseSetOnly, label = { Text("Set only") })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${shown.size} of ${efuses.size} fields · tap one for details",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("eFuse summary", efuseSummary(efuses)))
        }) { Text("Copy") }
        TextButton(onClick = vm::readEfuses, enabled = !state.busy) { Text("Reload") }
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
        shown.forEachIndexed { i, f ->
            if (i == 0 || shown[i - 1].block != f.block) {
                item {
                    Text(
                        "BLOCK${f.block}",
                        Modifier.padding(top = 12.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            item { EfuseRow(f, onClick = { selected = f }) }
        }
    }
    Progress(state)
    StatusAndLog(state, vm)

    selected?.let { f ->
        EfuseDialog(f, busy = state.busy, onBurn = { value -> vm.burnEfuse(f, value) }, onDismiss = { selected = null })
    }
}

@Composable
private fun EfuseRow(f: EfuseField, onClick: () -> Unit) {
    val value = f.displayValue()
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.name, Modifier.weight(1f).padding(end = 8.dp), fontSize = 13.sp)
            if (f.bits <= 32) {
                Text(
                    value,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = if (f.isZero) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (f.bits > 32) Text(value, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        val flags = listOfNotNull(
            "write-protected".takeIf { f.writeProtected },
            "read-protected".takeIf { f.readProtected },
        )
        if (flags.isNotEmpty()) {
            Text(flags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

/** Field details, and burning a new value behind a typed confirmation. */
@Composable
private fun EfuseDialog(f: EfuseField, busy: Boolean, onBurn: (ByteArray) -> Unit, onDismiss: () -> Unit) {
    val canBurn = !f.writeProtected && !f.readProtected
    val isFlag = f.bits == 1
    var input by remember { mutableStateOf(if (isFlag) "1" else "") }
    var confirm by remember { mutableStateOf("") }
    val parsed = if (canBurn && input.isNotBlank()) f.parseNewValue(input) else null
    val newValue = parsed?.getOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(f.name, fontSize = 18.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow("Block", "BLOCK${f.block}")
                InfoRow("Size", "${f.bits} bit${if (f.bits == 1) "" else "s"}")
                InfoRow("Value", if (f.bits <= 32) f.displayValue() else "")
                if (f.bits > 32) Text(f.displayValue(), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                if (f.writeProtected) Text("Write-protected: this field can no longer be changed.")
                if (f.readProtected) Text("Read-protected: its value is hidden from software.")

                if (canBurn) {
                    Text("Burn new value", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
                    if (!isFlag) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            label = { Text(if (f.bits <= 64) "New value (dec or 0x hex)" else "New value (hex bytes, LSB first)") },
                            singleLine = f.bits <= 64,
                            isError = parsed?.isFailure == true,
                            supportingText = parsed?.exceptionOrNull()?.let { e -> { Text(e.message ?: "Invalid value") } },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (newValue != null) {
                        Text(
                            "${f.displayValue()}  →  ${f.copy(value = newValue).displayValue()}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                    } else if (isFlag && parsed?.isFailure == true) {
                        Text("Already burned.")
                    }
                    if (f.isDangerous) {
                        Text(
                            "This fuse can permanently lock the chip: flashing, booting, debugging or other " +
                                "fuses. Burn it only if you know exactly what it does.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text("Burning is permanent and cannot be undone.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = { Text("Type ${f.name} to confirm") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            if (canBurn) {
                TextButton(
                    onClick = {
                        newValue?.let(onBurn)
                        onDismiss()
                    },
                    enabled = !busy && newValue != null && confirm.trim() == f.name,
                ) { Text("Burn", color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** 1 bit: Yes/No; up to 32 bits: number; longer: hex bytes as stored (LSB first). */
private fun EfuseField.displayValue(): String {
    if (readProtected) return "hidden"
    if (bits == 1) return if (value[0].toInt() and 1 == 1) "Yes" else "No"
    if (bits <= 32) {
        var v = 0L
        value.forEachIndexed { i, b -> v = v or ((b.toLong() and 0xFF) shl (8 * i)) }
        return if (bits <= 4) "$v" else "$v (0x%X)".format(v)
    }
    return value.joinToString(" ") { "%02x".format(it) }
}

private fun efuseSummary(efuses: List<EfuseField>): String = efuses.joinToString("\n") { f ->
    val flags = (if (f.writeProtected) " [WP]" else "") + (if (f.readProtected) " [RP]" else "")
    "BLOCK%-2d %-40s %s%s".format(f.block, f.name, f.displayValue(), flags)
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
