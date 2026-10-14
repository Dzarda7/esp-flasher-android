package io.github.dzarda7.espflasher

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
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors

data class ChipInfo(
    val chip: String,
    val mac: String,
    val flashSize: Int?,
    /** major * 100 + minor */
    val revision: Int?,
    /** Null if the chip doesn't report it (e.g. ESP32, ESP8266). */
    val security: List<Pair<String, Boolean>>?,
)

enum class Page { FLASH, DEVICE, EFUSE, MONITOR }

/** One binary to flash and where. */
data class FlashImage(val uri: Uri, val name: String, val offsetText: String)

data class UiState(
    val devices: List<UsbSerialDriver> = emptyList(),
    val connectedDevice: String? = null,
    val chipInfo: ChipInfo? = null,
    val images: List<FlashImage> = emptyList(),
    val readOffsetText: String = "0x0",
    val readSizeText: String = "",
    /** Null until read. */
    val efuses: List<EfuseField>? = null,
    val efuseFilter: String = "",
    val efuseSetOnly: Boolean = false,
    val baudRate: Int = DEFAULT_FLASH_BAUD,
    val monitorAfterFlash: Boolean = true,
    val busy: Boolean = false,
    val progress: Float = 0f,
    val monitoring: Boolean = false,
    val page: Page = Page.FLASH,
    /** One line saying what is happening or how the last action ended. */
    val status: String = "",
    val statusError: Boolean = false,
    /** Detailed log, hidden by default; for debugging and bug reports. */
    val log: List<String> = emptyList(),
    val showLog: Boolean = false,
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
    private var connectedIds: Pair<Int, Int>? = null // VID, PID of the connected device
    private var monitorJob: Job? = null

    // Only touched on the worker thread.
    private var inBootloader = false
    private var currentBaud = ROM_BAUD

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshDevices()
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            val ids = connectedIds
            if (device == null || ids != device.vendorId to device.productId) return
            viewModelScope.launch {
                // A USB-Serial-JTAG can drop off the bus on reset and come back; the bridge
                // reconnects to it. Only give up on the device if it stays gone.
                delay(DETACH_GRACE_MS)
                if (connectedIds == ids && findDriver(ids.first, ids.second) == null) {
                    monitorJob?.cancel()
                    // On the worker, so it runs after a task that is still failing on the dead port.
                    launch(worker) {
                        disconnectInternal()
                        status("Device unplugged", error = true)
                    }
                }
            }
        }
    }

    init {
        refreshDevices()
        ContextCompat.registerReceiver(
            app, detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun findDriver(vendorId: Int, productId: Int) = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        .firstOrNull { it.device.vendorId == vendorId && it.device.productId == productId }

    // getDeviceList() is a binder call that can block for seconds while devices churn
    // (e.g. a chip with erased flash boot-looping), so keep it off the main thread.
    fun refreshDevices() {
        viewModelScope.launch(Dispatchers.IO) {
            val devices = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            _state.update { it.copy(devices = devices) }
        }
    }

    fun setImageOffset(index: Int, text: String) = _state.update {
        it.copy(images = it.images.mapIndexed { i, img -> if (i == index) img.copy(offsetText = text) else img })
    }

    fun removeImage(index: Int) = _state.update { it.copy(images = it.images.filterIndexed { i, _ -> i != index }) }

    fun setReadOffset(text: String) = _state.update { it.copy(readOffsetText = text) }

    fun setReadSize(text: String) = _state.update { it.copy(readSizeText = text) }

    fun setBaudRate(baud: Int) = _state.update { it.copy(baudRate = baud) }

    fun setMonitorAfterFlash(enabled: Boolean) = _state.update { it.copy(monitorAfterFlash = enabled) }

    fun selectPage(page: Page) = _state.update { it.copy(page = page) }

    fun clearMonitor() = _state.update { it.copy(monitorLines = emptyList()) }

    fun setEfuseFilter(text: String) = _state.update { it.copy(efuseFilter = text) }

    fun toggleEfuseSetOnly() = _state.update { it.copy(efuseSetOnly = !it.efuseSetOnly) }

    fun toggleLog() = _state.update { it.copy(showLog = !it.showLog) }

    fun addImages(uris: List<Uri>) {
        val resolver = getApplication<Application>().contentResolver
        val names = uris.map { uri ->
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment ?: "image.bin"
        }
        _state.update { st ->
            val allNames = st.images.map { it.name } + names
            var apps = st.images.count { kind(it.name) == ImageKind.APP }
            val added = uris.zip(names) { uri, name ->
                FlashImage(uri, name, "0x%X".format(guessOffset(name, st.chipInfo?.chip, allNames, apps)))
                    .also { if (kind(name) == ImageKind.APP) apps++ }
            }
            st.copy(images = st.images + added)
        }
    }

    private enum class ImageKind { BOOTLOADER, PARTITIONS, APP }

    private fun kind(name: String) = name.lowercase().let {
        when {
            "bootloader" in it -> ImageKind.BOOTLOADER
            "partition" in it -> ImageKind.PARTITIONS
            else -> ImageKind.APP
        }
    }

    /**
     * Default offset from the usual ESP-IDF file names; the user can always edit it.
     * Alongside a bootloader or partition table an app goes to 0x10000; on its own the
     * first one is taken as a merged image at 0x0.
     */
    private fun guessOffset(name: String, chip: String?, allNames: List<String>, appsBefore: Int) = when (kind(name)) {
        ImageKind.BOOTLOADER -> when (chip) {
            "ESP32", "ESP32-S2" -> 0x1000
            "ESP32-C5", "ESP32-P4" -> 0x2000
            else -> 0x0
        }
        ImageKind.PARTITIONS -> 0x8000
        ImageKind.APP -> if (appsBefore == 0 && allNames.all { kind(it) == ImageKind.APP }) 0x0 else 0x10000
    }

    fun connect(driver: UsbSerialDriver) = runTask {
        disconnectInternal()
        status("Opening ${driver.device.productName ?: "device"}…")
        val bridge = openBridge(driver.device.vendorId, driver.device.productId)
        val s = try {
            Flasher(bridge)
        } catch (e: Exception) {
            bridge.close()
            throw e
        }
        flasher = s
        connectedIds = driver.device.vendorId to driver.device.productId
        _state.update { it.copy(connectedDevice = driver.device.productName ?: driver.device.deviceName) }

        val info = try {
            val flashSize = enterBootloader(s)
            ChipInfo(
                chip = s.chip(),
                mac = s.readMac(),
                flashSize = flashSize,
                revision = runCatching { s.chipRevision() }.getOrNull(),
                security = runCatching { s.securityInfo() }.getOrNull(),
            )
        } catch (e: Exception) {
            disconnectInternal()
            throw e
        }
        _state.update {
            it.copy(chipInfo = info, readSizeText = info.flashSize?.let { size -> "0x%X".format(size) } ?: "")
        }
        log("MAC ${info.mac}")
        status("Connected to ${info.chip}")
    }

    fun flash() = runTask {
        val s = flasher ?: error("Not connected")
        val images = _state.value.images.ifEmpty { error("No binaries selected") }
        val resolver = getApplication<Application>().contentResolver
        val loaded = images.map { img ->
            val data = resolver.openInputStream(img.uri)?.use { it.readBytes() } ?: error("Cannot read ${img.name}")
            Triple(img.name, parseOffset(img.offsetText), data)
        }
        val total = loaded.sumOf { it.third.size }

        ensureBootloader(s)
        val start = SystemClock.elapsedRealtime()
        var done = 0
        loaded.forEachIndexed { i, (name, offset, data) ->
            status("Flashing $name (${i + 1}/${loaded.size}) at 0x%X…".format(offset))
            s.flash(data, offset) { written, _ ->
                _state.update { it.copy(progress = (done + written).toFloat() / total) }
            }
            done += data.size
        }
        val seconds = (SystemClock.elapsedRealtime() - start) / 1000.0
        status(
            "Flashed and verified ${loaded.size} file${if (loaded.size == 1) "" else "s"}, ${total / 1024} KB " +
                "in %.1f s (%.0f kbit/s)".format(seconds, total * 8 / 1000.0 / seconds),
        )

        if (_state.value.monitorAfterFlash) {
            launchMonitor(s)
        } else {
            s.reset()
            inBootloader = false
            log("Target reset")
        }
    }

    fun eraseFlash() = runTask {
        val s = flasher ?: error("Not connected")
        ensureBootloader(s)
        status("Erasing flash, this can take a minute…")
        val start = SystemClock.elapsedRealtime()
        s.eraseFlash()
        status("Flash erased in %.1f s".format((SystemClock.elapsedRealtime() - start) / 1000.0))
    }

    /** Reads the range set on the Device page and writes it to [uri]. */
    fun readFlash(uri: Uri) = runTask {
        val s = flasher ?: error("Not connected")
        val offset = parseOffset(_state.value.readOffsetText)
        val size = parseOffset(_state.value.readSizeText)
        ensureBootloader(s)
        status("Reading ${size / 1024} KB at 0x%X…".format(offset))
        val start = SystemClock.elapsedRealtime()
        val data = s.readFlash(offset, size, progress)
        getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(data) }
            ?: error("Cannot write output file")
        val seconds = (SystemClock.elapsedRealtime() - start) / 1000.0
        status("Read ${size / 1024} KB in %.1f s (%.0f kbit/s)".format(seconds, size * 8 / 1000.0 / seconds))
    }

    fun readEfuses() = runTask {
        val s = flasher ?: error("Not connected")
        ensureBootloader(s)
        status("Reading eFuses…")
        val start = SystemClock.elapsedRealtime()
        val count = loadEfuses(s)
        status("Read $count eFuse fields in %.1f s".format((SystemClock.elapsedRealtime() - start) / 1000.0))
    }

    /** Irreversibly burns [value] into [field], then reads all eFuses back. */
    fun burnEfuse(field: EfuseField, value: ByteArray) = runTask {
        val s = flasher ?: error("Not connected")
        ensureBootloader(s)
        status("Burning ${field.name}…")
        s.burnEfuse(field, value)
        loadEfuses(s)
        status("Burned ${field.name}")
    }

    private fun loadEfuses(s: Flasher): Int {
        val revision = _state.value.chipInfo?.revision ?: error("Chip revision unknown")
        val efuses = s.readEfuses(revision, progress)
        _state.update { it.copy(efuses = efuses) }
        return efuses.size
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
            if (!bridge.write(bytes, bytes.size, 1000)) status("Monitor: write failed", error = true)
        }
    }

    fun disconnect() = runTask { disconnectInternal() }

    private fun disconnectInternal() {
        flasher?.close()
        flasher = null
        connectedIds = null
        inBootloader = false
        _state.update { it.copy(connectedDevice = null, chipInfo = null, efuses = null, progress = 0f) }
    }

    /**
     * Opens the current instance of the device with this VID:PID, retrying for a few seconds.
     * A chip that is boot-looping (e.g. erased flash on USB-Serial-JTAG) re-enumerates every
     * reset, so the device picked from the list may already be gone; once the port is open,
     * entering the bootloader stops the loop.
     */
    private suspend fun openBridge(vendorId: Int, productId: Int): UsbSerialBridge {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (true) {
            val driver = findDriver(vendorId, productId)
            if (driver != null) {
                if (!requestPermission(driver.device)) error("USB permission denied")
                try {
                    return UsbSerialBridge(usbManager, driver) { _, msg -> log(msg) }
                } catch (e: Exception) {
                    log("Open failed, retrying: ${e.message}")
                }
            }
            if (SystemClock.elapsedRealtime() > deadline) error("Device disappeared. Hold BOOT and reset the board, then try again")
            SystemClock.sleep(50)
        }
    }

    private val progress = Flasher.ProgressListener { done, total ->
        _state.update { it.copy(progress = done.toFloat() / total) }
    }

    /**
     * Resets the target into the ROM bootloader, loads the stub and switches to the selected
     * baud rate. Returns the detected flash size, which the loader also needs for reads.
     */
    private fun enterBootloader(s: Flasher): Int? {
        s.bridge.setBaudRate(ROM_BAUD)
        currentBaud = ROM_BAUD
        status("Connecting…")
        s.connect()
        inBootloader = true
        applyBaudRate(s)
        return runCatching { s.detectFlashSize() }.getOrNull()
    }

    private fun ensureBootloader(s: Flasher) {
        if (!inBootloader) enterBootloader(s) else applyBaudRate(s)
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
            _state.update { it.copy(monitoring = true, page = Page.MONITOR) }
            try {
                s.bridge.setBaudRate(MONITOR_BAUD)
                currentBaud = MONITOR_BAUD
                s.reset()
                inBootloader = false

                val buf = ByteArray(4096)
                // Decode as a stream: a multi-byte UTF-8 character can be split across reads.
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                val bytes = ByteBuffer.allocate(buf.size + 8)
                val chars = CharBuffer.allocate(buf.size + 8)
                while (isActive) {
                    val n = s.bridge.read(buf, buf.size, 100)
                    if (n < 0) {
                        status("Monitor: connection lost", error = true)
                        break
                    }
                    if (n == 0) continue
                    // Through Buffer: the covariant ByteBuffer/CharBuffer overrides of flip() and
                    // clear() don't exist before Android 14.
                    (bytes.put(buf, 0, n) as Buffer).flip()
                    decoder.decode(bytes, chars, false)
                    bytes.compact()
                    (chars as Buffer).flip()
                    appendMonitor(chars.toString())
                    (chars as Buffer).clear()
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) status("Monitor: ${e.message}", error = true)
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
        _state.update { it.copy(busy = true, progress = 0f) }
        val monitor = monitorJob
        monitor?.cancel()
        viewModelScope.launch(worker) {
            try {
                monitor?.join()
                block()
            } catch (e: Exception) {
                status(e.message ?: e.toString(), error = true)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun log(msg: String) = _state.update { it.copy(log = (it.log + msg).takeLast(500)) }

    private fun status(msg: String, error: Boolean = false) {
        log(msg)
        _state.update { it.copy(status = msg, statusError = error) }
    }

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
        getApplication<Application>().unregisterReceiver(detachReceiver)
        monitorJob?.cancel()
        flasher?.close()
        worker.close()
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "io.github.dzarda7.espflasher.USB_PERMISSION"
        private const val ROM_BAUD = 115200
        private const val MONITOR_BAUD = 115200
        private const val MAX_MONITOR_LINES = 2000
        private const val DETACH_GRACE_MS = 3500L

        fun parseOffset(text: String): Int {
            val t = text.trim().lowercase()
            return if (t.startsWith("0x")) t.substring(2).toLong(16).toInt() else t.toLong().toInt()
        }
    }
}
