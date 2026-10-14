package com.espflasher

import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import com.hoho.android.usbserial.driver.CommonUsbSerialPort
import com.hoho.android.usbserial.driver.SerialTimeoutException
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Serial transport used by the native port (android_port.c).
 *
 * The methods called from native code ([write], [read], [setLines], [setBaudRate],
 * [isUsbJtag], [log]) must keep their JNI signatures and must not throw.
 */
class UsbSerialBridge(
    private val usbManager: UsbManager,
    driver: UsbSerialDriver,
    private val onLog: (level: Int, message: String) -> Unit,
) : Closeable {

    private val vendorId = driver.device.vendorId
    private val productId = driver.device.productId
    private var port: UsbSerialPort
    private var baudRate = 115200
    private var txDrainedAtNs = 0L // when the bridge will have sent everything written so far

    // Received data, filled by the reader thread. A USB-UART bridge has only a small RX FIFO
    // and no flow control towards the chip, so if the host stops polling while data streams
    // in (e.g. a flash read) bytes are lost. Like the Linux kernel driver, keep several USB
    // read requests queued at all times and buffer what arrives until it is asked for.
    private val lock = ReentrantLock()
    private val dataAvailable = lock.newCondition()
    private val rx = ByteArray(RX_BUFFER_SIZE)
    private var rxStart = 0
    private var rxCount = 0
    private var rxError: Exception? = null
    private var reader: Thread? = null

    init {
        port = open(driver)
    }

    private fun open(driver: UsbSerialDriver): UsbSerialPort {
        val connection = usbManager.openDevice(driver.device)
            ?: throw IOException("Cannot open ${driver.device.deviceName} (no permission?)")
        val p = driver.ports[0]
        p.open(connection)
        p.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        lock.withLock {
            rxStart = 0
            rxCount = 0
            rxError = null
        }
        (p as CommonUsbSerialPort).setReadQueue(READ_QUEUE, READ_SIZE)
        // Registered before it starts, so an immediate failure is not mistaken for close().
        reader = thread(start = false, name = "usb-rx", isDaemon = true) { readLoop(p) }.also { it.start() }
        return p
    }

    private fun readLoop(p: UsbSerialPort) {
        val buf = ByteArray(READ_SIZE)
        try {
            while (true) {
                val n = p.read(buf, 0) // blocks; usb-serial keeps READ_QUEUE requests queued
                lock.withLock {
                    val space = minOf(n, RX_BUFFER_SIZE - rxCount)
                    if (space < n) Log.w(TAG, "RX buffer full, dropped ${n - space} bytes")
                    for (i in 0 until space) rx[(rxStart + rxCount + i) % RX_BUFFER_SIZE] = buf[i]
                    rxCount += space
                    dataAvailable.signalAll()
                }
            }
        } catch (e: Exception) {
            lock.withLock {
                if (reader === Thread.currentThread()) rxError = e // not when closed on purpose
                dataAvailable.signalAll()
            }
        }
    }

    override fun close() {
        reader = null
        runCatching { port.close() } // makes the reader's blocking read throw
    }

    // ---- called from native ----

    /**
     * Through a USB-UART bridge, writes are paced to the UART baud rate so the bridge's TX FIFO
     * never fills. Written at full USB speed, a CP2102N on a Pixel 10a fails within a few KB of
     * a flash write: while its FIFO is full it NAKs the host, and after a while a transfer ends
     * in a USB protocol error and the CP2102N stops answering altogether until it is replugged.
     * The same traffic works from a Linux PC. With at most one chunk queued the bridge never
     * has to NAK, and since the UART is the bottleneck anyway this costs no throughput.
     * The USB-Serial-JTAG has no UART behind it, so it is written at full speed.
     */
    fun write(data: ByteArray, len: Int, timeoutMs: Int): Boolean {
        if (isUsbJtag()) return writeNow(data.copyOf(len), timeoutMs)

        val chunkNs = { n: Int -> n * 10 * 1_000_000_000L / baudRate } // 10 bits per byte on the wire
        var off = 0
        while (off < len) {
            val n = minOf(TX_CHUNK, len - off)
            val wait = txDrainedAtNs - System.nanoTime() - chunkNs(TX_CHUNK)
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            // The library passes what is left of its response timer, which may be ~0.
            if (!writeNow(data.copyOfRange(off, off + n), timeoutMs.coerceAtLeast(1000))) return false
            txDrainedAtNs = maxOf(txDrainedAtNs, System.nanoTime()) + chunkNs(n)
            off += n
        }
        return true
    }

    private fun writeNow(bytes: ByteArray, timeoutMs: Int): Boolean {
        for (attempt in 0..1) {
            try {
                port.write(bytes, timeoutMs.coerceAtLeast(1))
                return true
            } catch (e: SerialTimeoutException) {
                // Device is still there, just not reading (e.g. busy booting). Not a disconnect.
                return false
            } catch (e: IOException) {
                Log.w(TAG, "write failed: ${e.message}")
                if (!reconnect()) return false
            }
        }
        return false
    }

    /** Returns 0..[len] bytes copied into [dest] (0 on timeout), or -1 if the device is gone. */
    fun read(dest: ByteArray, len: Int, timeoutMs: Int): Int {
        lock.withLock {
            var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1).toLong())
            while (rxCount == 0 && rxError == null) {
                if (nanos <= 0) return 0
                nanos = dataAvailable.awaitNanos(nanos)
            }
            if (rxCount > 0) {
                val n = minOf(len, rxCount)
                for (i in 0 until n) dest[i] = rx[(rxStart + i) % RX_BUFFER_SIZE]
                rxStart = (rxStart + n) % RX_BUFFER_SIZE
                rxCount -= n
                return n
            }
            Log.w(TAG, "read failed: ${rxError?.message}")
        }
        return if (reconnect()) 0 else -1
    }

    fun setLines(dtr: Boolean, rts: Boolean) {
        try {
            port.setDTR(dtr)
            port.setRTS(rts)
        } catch (e: IOException) {
            Log.w(TAG, "setLines failed", e)
        }
    }

    fun setBaudRate(baud: Int): Boolean = try {
        port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        baudRate = baud
        true
    } catch (e: Exception) {
        Log.w(TAG, "setBaudRate($baud) failed", e)
        false
    }

    fun isUsbJtag(): Boolean = vendorId == ESPRESSIF_VID && productId == USB_JTAG_SERIAL_PID

    /**
     * The built-in USB-Serial-JTAG can drop off the bus when the chip resets.
     * Wait for it to come back and reopen it.
     *
     * Android ties USB permission to the device instance, so this only succeeds
     * without a prompt if the user granted access via the USB_DEVICE_ATTACHED dialog
     * with "always open" checked.
     */
    private fun reconnect(): Boolean {
        if (!isUsbJtag()) return false
        close()
        val deadline = SystemClock.uptimeMillis() + RECONNECT_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
            val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
                .firstOrNull { it.device.vendorId == vendorId && it.device.productId == productId }
            if (driver != null && usbManager.hasPermission(driver.device)) {
                try {
                    port = open(driver)
                    Log.i(TAG, "reconnected")
                    return true
                } catch (e: IOException) {
                    Log.d(TAG, "reopen not ready yet: ${e.message}")
                }
            }
        }
        Log.w(TAG, "device did not come back within $RECONNECT_TIMEOUT_MS ms")
        return false
    }

    fun log(level: Int, message: String) = onLog(level, message)

    companion object {
        private const val TAG = "UsbSerialBridge"
        private const val RECONNECT_TIMEOUT_MS = 3000L
        private const val TX_CHUNK = 256
        private const val READ_QUEUE = 4
        private const val READ_SIZE = 16 * 1024
        private const val RX_BUFFER_SIZE = 1 shl 20
        const val ESPRESSIF_VID = 0x303A
        const val USB_JTAG_SERIAL_PID = 0x1001
    }
}
