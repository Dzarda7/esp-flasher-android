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
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

data class ChipInfo(val chip: String, val mac: String, val flashSize: Int?)

data class UiState(
    val devices: List<UsbSerialDriver> = emptyList(),
    val connectedDevice: String? = null,
    val chipInfo: ChipInfo? = null,
    val firmwareUri: Uri? = null,
    val firmwareName: String? = null,
    val offsetText: String = "0x0",
    val busy: Boolean = false,
    val progress: Float? = null,
    val log: List<String> = emptyList(),
)

class FlasherViewModel(app: Application) : AndroidViewModel(app) {

    private val usbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager

    // The native session is single-threaded: run every flasher call here.
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var session: FlasherSession? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refreshDevices()
    }

    fun refreshDevices() {
        _state.update { it.copy(devices = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)) }
    }

    fun setOffset(text: String) = _state.update { it.copy(offsetText = text) }

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
            FlasherSession(bridge)
        } catch (e: Exception) {
            bridge.close()
            throw e
        }
        session = s
        _state.update { it.copy(connectedDevice = driver.device.productName ?: driver.device.deviceName) }

        log("Connecting...")
        s.connect(useStub = true)
        val chip = s.chip
        if (chip != "ESP8266") {
            s.changeBaudRate(FAST_BAUD)
            log("Baud rate set to $FAST_BAUD")
        }
        val info = ChipInfo(
            chip = chip,
            mac = s.readMac(),
            flashSize = runCatching { s.detectFlashSize() }.getOrNull(),
        )
        _state.update { it.copy(chipInfo = info) }
        log("Connected to $chip, MAC ${info.mac}")
    }

    fun flash() = runTask {
        val s = session ?: error("Not connected")
        val uri = _state.value.firmwareUri ?: error("No firmware selected")
        val offset = parseOffset(_state.value.offsetText)
        val data = getApplication<Application>().contentResolver.openInputStream(uri)
            ?.use { it.readBytes() } ?: error("Cannot read firmware file")

        log("Flashing ${data.size} bytes at 0x%X...".format(offset))
        _state.update { it.copy(progress = 0f) }
        s.flash(data, offset) { written, total ->
            _state.update { it.copy(progress = written.toFloat() / total) }
        }
        log("Flash done, verified. Resetting target.")
        s.reset()
    }

    fun disconnect() = runTask { disconnectInternal() }

    private fun disconnectInternal() {
        session?.close()
        session = null
        _state.update { it.copy(connectedDevice = null, chipInfo = null, progress = null) }
    }

    private fun runTask(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch(worker) {
            try {
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
        session?.close()
        worker.close()
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.espflasher.USB_PERMISSION"
        private const val FAST_BAUD = 460800

        fun parseOffset(text: String): Int {
            val t = text.trim().lowercase()
            return if (t.startsWith("0x")) t.substring(2).toLong(16).toInt() else t.toLong().toInt()
        }
    }
}
