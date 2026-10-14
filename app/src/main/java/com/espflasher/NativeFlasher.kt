package com.espflasher

import java.io.Closeable

/** Raw JNI bindings to flasher_jni.c. Use [FlasherSession] instead. */
object NativeFlasher {
    init {
        System.loadLibrary("espflasher")
    }

    fun interface ProgressListener {
        fun onProgress(written: Int, total: Int)
    }

    external fun nativeCreate(bridge: UsbSerialBridge): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeConnect(handle: Long, useStub: Boolean): Int
    external fun nativeChangeBaudRate(handle: Long, baud: Int): Int
    external fun nativeGetTarget(handle: Long): Int
    external fun nativeReadMac(handle: Long, out: ByteArray): Int
    external fun nativeDetectFlashSize(handle: Long, out: IntArray): Int
    external fun nativeFlash(handle: Long, data: ByteArray, offset: Int, listener: ProgressListener): Int
    external fun nativeReset(handle: Long)
}

class FlasherException(val code: Int) : Exception("${errorName(code)} ($code)") {
    companion object {
        // Order matches esp_loader_error_t.
        private val NAMES = listOf(
            "SUCCESS", "FAIL", "TIMEOUT", "IMAGE_SIZE", "INVALID_MD5", "INVALID_PARAM",
            "INVALID_TARGET", "UNSUPPORTED_CHIP", "UNSUPPORTED_FUNC", "INVALID_RESPONSE",
            "EFUSE_BLOCK_IN_USE", "EFUSE_BURN_FAILED",
        )

        fun errorName(code: Int) = NAMES.getOrElse(code) { "UNKNOWN" }
    }
}

/**
 * One connection to a target. Not thread-safe: call everything from the same
 * single worker thread.
 */
class FlasherSession(private val bridge: UsbSerialBridge) : Closeable {
    private var handle = NativeFlasher.nativeCreate(bridge).also {
        check(it != 0L) { "Failed to create native session" }
    }

    private fun throwIfError(code: Int) {
        if (code != 0) throw FlasherException(code)
    }

    fun connect(useStub: Boolean = true) = throwIfError(NativeFlasher.nativeConnect(handle, useStub))

    fun changeBaudRate(baud: Int) = throwIfError(NativeFlasher.nativeChangeBaudRate(handle, baud))

    val chip: String
        get() = CHIP_NAMES.getOrElse(NativeFlasher.nativeGetTarget(handle)) { "Unknown" }

    fun readMac(): String {
        val mac = ByteArray(6)
        throwIfError(NativeFlasher.nativeReadMac(handle, mac))
        return mac.joinToString(":") { "%02X".format(it) }
    }

    fun detectFlashSize(): Int {
        val out = IntArray(1)
        throwIfError(NativeFlasher.nativeDetectFlashSize(handle, out))
        return out[0]
    }

    fun flash(data: ByteArray, offset: Int, onProgress: NativeFlasher.ProgressListener) =
        throwIfError(NativeFlasher.nativeFlash(handle, data, offset, onProgress))

    fun reset() = NativeFlasher.nativeReset(handle)

    override fun close() {
        if (handle != 0L) {
            NativeFlasher.nativeDestroy(handle)
            handle = 0L
        }
        bridge.close()
    }

    companion object {
        // Order matches target_chip_t.
        val CHIP_NAMES = listOf(
            "ESP8266", "ESP32", "ESP32-S2", "ESP32-C3", "ESP32-S3", "ESP32-C2", "ESP32-C5",
            "ESP32-H2", "ESP32-C6", "ESP32-P4", "ESP32-C61", "ESP32-S31", "ESP32-H21", "ESP32-H4",
        )
    }
}
