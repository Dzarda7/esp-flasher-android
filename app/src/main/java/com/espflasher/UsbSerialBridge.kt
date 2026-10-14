package com.espflasher

import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.Closeable
import java.io.IOException

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

    // usb-serial-for-android reads whole USB packets, which can be more than the
    // native side asked for, so keep the surplus here.
    private val rxBuf = ByteArray(16 * 1024)
    private var rxPos = 0
    private var rxLen = 0

    init {
        port = open(driver)
    }

    private fun open(driver: UsbSerialDriver): UsbSerialPort {
        val connection = usbManager.openDevice(driver.device)
            ?: throw IOException("Cannot open ${driver.device.deviceName} (no permission?)")
        val p = driver.ports[0]
        p.open(connection)
        p.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        rxPos = 0
        rxLen = 0
        return p
    }

    override fun close() {
        runCatching { port.close() }
    }

    // ---- called from native ----

    fun write(data: ByteArray, len: Int, timeoutMs: Int): Boolean {
        val bytes = data.copyOf(len)
        for (attempt in 0..1) {
            try {
                port.write(bytes, timeoutMs.coerceAtLeast(1))
                return true
            } catch (e: IOException) {
                Log.w(TAG, "write failed: ${e.message}")
                if (!reconnect()) return false
            }
        }
        return false
    }

    /** Returns 0..[len] bytes copied into [dest], or -1 if the device is gone. */
    fun read(dest: ByteArray, len: Int, timeoutMs: Int): Int {
        if (rxPos == rxLen) {
            rxPos = 0
            rxLen = try {
                port.read(rxBuf, timeoutMs.coerceAtLeast(1))
            } catch (e: IOException) {
                Log.w(TAG, "read failed: ${e.message}")
                return if (reconnect()) 0 else -1
            }
        }
        val n = minOf(len, rxLen - rxPos)
        System.arraycopy(rxBuf, rxPos, dest, 0, n)
        rxPos += n
        return n
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
        const val ESPRESSIF_VID = 0x303A
        const val USB_JTAG_SERIAL_PID = 0x1001
    }
}
