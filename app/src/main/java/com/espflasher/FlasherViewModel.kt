package com.espflasher

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

data class ChipInfo(val chip: String, val mac: String, val flashSize: Int?)

enum class ConsoleTab { LOG, MONITOR }

data class UiState(
    val devices: List<UsbSerialDriver> = emptyList(),
    val connectedDevice: String? = null,
    val chipInfo: ChipInfo? = null,
    val firmwareUri: Uri? = null,
    val firmwareName: String? = null,
    val offsetText: String = "0x0",
    val baudRate: Int = DEFAULT_FLASH_BAUD,
    val monitorAfterFlash: Boolean = true,
    val busy: Boolean = false,
    val progress: Float? = null,
    val monitoring: Boolean = false,
    val tab: ConsoleTab = ConsoleTab.LOG,
    val log: List<String> = emptyList(),
    /** Raw monitor lines, ANSI escapes included; the last one may still be incomplete. */
    val monitorLines: List<String> = emptyList(),
)

val FLASH_BAUD_RATES = listOf(115200, 460800, 921600, 1500000, 2000000)
const val DEFAULT_FLASH_BAUD = 921600

class FlasherViewModel(app: Application) : AndroidViewModel(app) {

    private val usbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager

    // The native flasher is single-threaded: run every flasher call here.
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var flasher: Flasher? = null
    private var monitorJob: Job? = null

    // Only touched on the worker thread.
    private var inBootloader = false
    private var currentBaud = ROM_BAUD

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refreshDevices()
    }

    fun refreshDevices() {
        _state.update { it.copy(devices = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)) }
    }

    fun setOffset(text: String) = _state.update { it.copy(offsetText = text) }

    fun setBaudRate(baud: Int) = _state.update { it.copy(baudRate = baud) }

    fun setMonitorAfterFlash(enabled: Boolean) = _state.update { it.copy(monitorAfterFlash = enabled) }

    fun selectTab(tab: ConsoleTab) = _state.update { it.copy(tab = tab) }

    fun clearConsole() = _state.update {
        if (it.tab == ConsoleTab.LOG) it.copy(log = emptyList()) else it.copy(monitorLines = emptyList())
    }

    fun setFirmware(uri: Uri) {
        val name = getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        _state.update { it.copy(firmwareUri = uri, firmwareName = name ?: uri.lastPathSegment) }
    }

    fun connect(driver: UsbSerialDriver) = runTask {
        if (!requestPermission(driver.device)) {
            log("USB permission denied")
            return@runTask
        }
        disconnectInternal()

        val bridge = UsbSerialBridge(usbManager, driver) { _, msg -> log(msg) }
        val s = try {
            Flasher(bridge)
        } catch (e: Exception) {
            bridge.close()
            throw e
        }
        flasher = s
        _state.update { it.copy(connectedDevice = driver.device.productName ?: driver.device.deviceName) }

        enterBootloader(s)
        val info = ChipInfo(
            chip = s.chip(),
            mac = s.readMac(),
            flashSize = runCatching { s.detectFlashSize() }.getOrNull(),
        )
        _state.update { it.copy(chipInfo = info) }
        log("Connected to ${info.chip}, MAC ${info.mac}")
    }

    fun flash() = runTask {
        val s = flasher ?: error("Not connected")
        val uri = _state.value.firmwareUri ?: error("No firmware selected")
        val offset = parseOffset(_state.value.offsetText)
        val data = getApplication<Application>().contentResolver.openInputStream(uri)
            ?.use { it.readBytes() } ?: error("Cannot read firmware file")

        if (!inBootloader) enterBootloader(s) else applyBaudRate(s)

        log("Flashing ${data.size} bytes at 0x%X...".format(offset))
        _state.update { it.copy(progress = 0f) }
        val start = SystemClock.elapsedRealtime()
        s.flash(data, offset) { written, total ->
            _state.update { it.copy(progress = written.toFloat() / total) }
        }
        val seconds = (SystemClock.elapsedRealtime() - start) / 1000.0
        log("Flash done and verified in %.1f s (%.0f kbit/s)".format(seconds, data.size * 8 / 1000.0 / seconds))

        if (_state.value.monitorAfterFlash) {
            launchMonitor(s)
        } else {
            s.reset()
            inBootloader = false
            log("Target reset")
        }
    }

    /** Resets the target into its application and shows its serial output. */
    fun startMonitor() {
        val s = flasher ?: return
        if (_state.value.busy) return
        monitorJob?.cancel()
        launchMonitor(s)
    }

    fun stopMonitor() {
        monitorJob?.cancel()
    }

    fun sendToMonitor(text: String) {
        val bridge = flasher?.bridge ?: return
        val bytes = (text + "\n").toByteArray()
        // usb-serial-for-android allows writing while another thread is reading.
        viewModelScope.launch(Dispatchers.IO) {
            if (!bridge.write(bytes, bytes.size, 1000)) log("Monitor: write failed")
        }
    }

    fun disconnect() = runTask { disconnectInternal() }

    private fun disconnectInternal() {
        flasher?.close()
        flasher = null
        inBootloader = false
        _state.update { it.copy(connectedDevice = null, chipInfo = null, progress = null) }
    }

    /** Resets the target into the ROM bootloader, loads the stub and switches to the selected baud rate. */
    private fun enterBootloader(s: Flasher) {
        s.bridge.setBaudRate(ROM_BAUD)
        currentBaud = ROM_BAUD
        log("Connecting...")
        s.connect()
        inBootloader = true
        applyBaudRate(s)
    }

    private fun applyBaudRate(s: Flasher) {
        val baud = _state.value.baudRate
        // Baud rate is meaningless on the USB-Serial-JTAG and ESP8266 can't change it.
        if (baud == currentBaud || s.bridge.isUsbJtag() || s.chip() == "ESP8266") return
        s.changeBaudRate(baud)
        currentBaud = baud
        log("Baud rate set to $baud")
    }

    private fun launchMonitor(s: Flasher) {
        monitorJob = viewModelScope.launch(worker) {
            _state.update { it.copy(monitoring = true, tab = ConsoleTab.MONITOR) }
            try {
                s.bridge.setBaudRate(MONITOR_BAUD)
                currentBaud = MONITOR_BAUD
                s.reset()
                inBootloader = false

                val buf = ByteArray(4096)
                while (isActive) {
                    val n = s.bridge.read(buf, buf.size, 100)
                    if (n < 0) {
                        log("Monitor: connection lost")
                        break
                    }
                    if (n > 0) appendMonitor(String(buf, 0, n, Charsets.UTF_8))
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) log("Monitor error: ${e.message}")
            } finally {
                _state.update { it.copy(monitoring = false) }
            }
        }
    }

    private fun appendMonitor(text: String) = _state.update { st ->
        val parts = text.replace("\r", "").split('\n')
        val lines = st.monitorLines.toMutableList()
        lines.add((lines.removeLastOrNull() ?: "") + parts[0])
        lines.addAll(parts.drop(1))
        st.copy(monitorLines = lines.takeLast(MAX_MONITOR_LINES))
    }

    /** Runs [block] on the worker, stopping the monitor first since both use the serial port. */
    private fun runTask(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        val monitor = monitorJob
        monitor?.cancel()
        viewModelScope.launch(worker) {
            try {
                monitor?.join()
                block()
            } catch (e: Exception) {
                log("Error: ${e.message}")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun log(msg: String) = _state.update { it.copy(log = (it.log + msg).takeLast(500)) }

    private suspend fun requestPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true

        val app = getApplication<Application>()
        val result = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                result.complete(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(app.packageName)
            usbManager.requestPermission(device, PendingIntent.getBroadcast(app, 0, intent, flags))
            return result.await()
        } finally {
            app.unregisterReceiver(receiver)
        }
    }

    override fun onCleared() {
        monitorJob?.cancel()
        flasher?.close()
        worker.close()
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.espflasher.USB_PERMISSION"
        private const val ROM_BAUD = 115200
        private const val MONITOR_BAUD = 115200
        private const val MAX_MONITOR_LINES = 2000

        fun parseOffset(text: String): Int {
            val t = text.trim().lowercase()
            return if (t.startsWith("0x")) t.substring(2).toLong(16).toInt() else t.toLong().toInt()
        }
    }
}
