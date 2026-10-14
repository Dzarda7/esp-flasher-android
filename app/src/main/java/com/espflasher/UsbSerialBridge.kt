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
 * [isUsbJtag], [reconnect], [log]) must keep their JNI signatures and must not throw.
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

    fun write(data: ByteArray, len: Int, timeoutMs: Int): Boolean = try {
        port.write(data.copyOf(len), timeoutMs.coerceAtLeast(1))
        true
    } catch (e: IOException) {
        Log.w(TAG, "write failed", e)
        false
    }

    /** Returns 0..[len] bytes copied into [dest], or -1 on error. */
    fun read(dest: ByteArray, len: Int, timeoutMs: Int): Int = try {
        if (rxPos == rxLen) {
            rxPos = 0
            rxLen = port.read(rxBuf, timeoutMs.coerceAtLeast(1))
        }
        val n = minOf(len, rxLen - rxPos)
        System.arraycopy(rxBuf, rxPos, dest, 0, n)
        rxPos += n
        n
    } catch (e: IOException) {
        Log.w(TAG, "read failed", e)
        -1
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
     * Chips using the built-in USB-Serial-JTAG re-enumerate on reset, which
     * invalidates the USB connection. Wait for the device to come back and reopen it.
     *
     * Android ties USB permission to the device instance, so this only succeeds
     * without a prompt if the user granted access via the USB_DEVICE_ATTACHED dialog
     * with "always open" checked.
     */
    fun reconnect(timeoutMs: Int): Boolean {
        close()
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        SystemClock.sleep(200)
        while (SystemClock.uptimeMillis() < deadline) {
            val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
                .firstOrNull { it.device.vendorId == vendorId && it.device.productId == productId }
            if (driver != null && usbManager.hasPermission(driver.device)) {
                try {
                    port = open(driver)
                    return true
                } catch (e: IOException) {
                    Log.d(TAG, "reopen not ready yet: ${e.message}")
                }
            }
            SystemClock.sleep(100)
        }
        Log.w(TAG, "device did not come back within $timeoutMs ms")
        return false
    }

    fun log(level: Int, message: String) = onLog(level, message)

    companion object {
        private const val TAG = "UsbSerialBridge"
        const val ESPRESSIF_VID = 0x303A
        const val USB_JTAG_SERIAL_PID = 0x1001
    }
}
