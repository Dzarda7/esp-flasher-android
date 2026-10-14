package com.espflasher

import java.io.Closeable

/**
 * One esp-serial-flasher loader (flasher_jni.c) talking over [bridge].
 * Not thread-safe: call everything from the same worker thread.
 */
class Flasher(val bridge: UsbSerialBridge) : Closeable {
    private var handle = nativeCreate(bridge)

    init {
        check(handle != 0L) { "Failed to create native flasher" }
    }

    fun connect() = check(nativeConnect(handle), "connect")

    fun changeBaudRate(baud: Int) = check(nativeChangeBaudRate(handle, baud), "change baud rate")

    fun chip(): String = CHIP_NAMES.getOrElse(nativeGetTarget(handle)) { "Unknown" }

    fun readMac(): String {
        val mac = ByteArray(6)
        check(nativeReadMac(handle, mac), "read MAC")
        return mac.joinToString(":") { "%02X".format(it) }
    }

    fun detectFlashSize(): Int {
        val size = IntArray(1)
        check(nativeDetectFlashSize(handle, size), "detect flash size")
        return size[0]
    }

    fun flash(data: ByteArray, offset: Int, onProgress: ProgressListener) =
        check(nativeFlash(handle, data, offset, onProgress), "flash")

    fun reset() = nativeReset(handle)

    override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0L
        bridge.close()
    }

    fun interface ProgressListener {
        fun onProgress(written: Int, total: Int)
    }

    private fun check(err: Int, what: String) {
        if (err != 0) throw Exception("$what failed: ${ERROR_NAMES.getOrElse(err) { "error $err" }}")
    }

    private external fun nativeCreate(bridge: UsbSerialBridge): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeConnect(handle: Long): Int
    private external fun nativeChangeBaudRate(handle: Long, baud: Int): Int
    private external fun nativeGetTarget(handle: Long): Int
    private external fun nativeReadMac(handle: Long, out: ByteArray): Int
    private external fun nativeDetectFlashSize(handle: Long, out: IntArray): Int
    private external fun nativeFlash(handle: Long, data: ByteArray, offset: Int, listener: ProgressListener): Int
    private external fun nativeReset(handle: Long)

    companion object {
        init {
            System.loadLibrary("espflasher")
        }

        // Order matches target_chip_t.
        private val CHIP_NAMES = listOf(
            "ESP8266", "ESP32", "ESP32-S2", "ESP32-C3", "ESP32-S3", "ESP32-C2", "ESP32-C5",
            "ESP32-H2", "ESP32-C6", "ESP32-P4", "ESP32-C61", "ESP32-S31", "ESP32-H21", "ESP32-H4",
        )

        // Order matches esp_loader_error_t.
        private val ERROR_NAMES = listOf(
            "SUCCESS", "FAIL", "TIMEOUT", "IMAGE_SIZE", "INVALID_MD5", "INVALID_PARAM",
            "INVALID_TARGET", "UNSUPPORTED_CHIP", "UNSUPPORTED_FUNC", "INVALID_RESPONSE",
            "EFUSE_BLOCK_IN_USE", "EFUSE_BURN_FAILED",
        )
    }
}
