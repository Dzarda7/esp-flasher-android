package com.espflasher

import java.io.Closeable

/** One eFuse field; [value] is LSB first. */
data class EfuseField(
    val name: String,
    val block: Int,
    val bits: Int,
    val value: ByteArray,
    val writeProtected: Boolean,
    val readProtected: Boolean,
)

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

    /** Revision as major * 100 + minor (v1.2 -> 102). */
    fun chipRevision(): Int {
        val rev = IntArray(1)
        check(nativeGetChipRevision(handle, rev), "read chip revision")
        return rev[0]
    }

    /** Named security flags. */
    fun securityInfo(): List<Pair<String, Boolean>> {
        val v = IntArray(SECURITY_FLAGS.size)
        check(nativeGetSecurityInfo(handle, v), "read security info")
        return SECURITY_FLAGS.mapIndexed { i, name -> name to (v[i] != 0) }
    }

    fun eraseFlash() = check(nativeEraseFlash(handle), "erase flash")

    fun readFlash(offset: Int, size: Int, onProgress: ProgressListener): ByteArray {
        val data = ByteArray(size)
        var done = 0
        while (done < size) {
            val chunk = minOf(READ_CHUNK, size - done)
            check(nativeReadFlash(handle, data, offset, done, chunk), "read flash")
            done += chunk
            onProgress.onProgress(done, size)
        }
        return data
    }

    /** Reads every eFuse field of the chip; one chip round-trip per field word. */
    fun readEfuses(revision: Int, onProgress: ProgressListener): List<EfuseField> {
        val count = nativeEfuseFieldCount(handle, revision)
        if (count < 0) check(-count, "load eFuse table")

        val meta = IntArray(4) // block, bits, wr_dis bit, rd_dis bit
        val protectBits = IntArray(count * 2)
        val fields = (0 until count).map { i ->
            val name = nativeEfuseFieldInfo(handle, i, meta)
            val value = ByteArray((meta[1] + 7) / 8)
            check(nativeEfuseReadField(handle, i, value), "read eFuse $name")
            protectBits[i * 2] = meta[2]
            protectBits[i * 2 + 1] = meta[3]
            onProgress.onProgress(i + 1, count)
            EfuseField(name, meta[0], meta[1], value, writeProtected = false, readProtected = false)
        }

        // A field's protection is one bit of the WR_DIS / RD_DIS fields.
        val wrDis = fields.find { it.name == "WR_DIS" }?.value
        val rdDis = fields.find { it.name == "RD_DIS" }?.value
        fun isSet(v: ByteArray?, bit: Int) =
            v != null && bit != NO_BIT && bit / 8 < v.size && (v[bit / 8].toInt() shr (bit % 8)) and 1 == 1
        return fields.mapIndexed { i, f ->
            f.copy(writeProtected = isSet(wrDis, protectBits[i * 2]), readProtected = isSet(rdDis, protectBits[i * 2 + 1]))
        }
    }

    override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0L
        bridge.close()
    }

    fun interface ProgressListener {
        fun onProgress(done: Int, total: Int)
    }

    private fun check(err: Int, what: String) {
        if (err == 0) return
        val name = ERROR_NAMES.getOrElse(err) { "error $err" }
        val hint = when (name) {
            "TIMEOUT" -> "no response from the chip. Check the cable, or hold BOOT and try again"
            "INVALID_RESPONSE" -> "garbled response. Try a lower baud rate"
            "INVALID_MD5" -> "verification failed. Try a lower baud rate"
            "IMAGE_SIZE" -> "the image does not fit in flash"
            "INVALID_TARGET", "UNSUPPORTED_CHIP" -> "this chip is not supported"
            else -> "unexpected error"
        }
        throw Exception("$what failed: $hint [$name]")
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
    private external fun nativeGetChipRevision(handle: Long, out: IntArray): Int
    private external fun nativeGetSecurityInfo(handle: Long, out: IntArray): Int
    private external fun nativeEraseFlash(handle: Long): Int
    private external fun nativeEfuseFieldCount(handle: Long, revision: Int): Int
    private external fun nativeEfuseFieldInfo(handle: Long, index: Int, meta: IntArray): String
    private external fun nativeEfuseReadField(handle: Long, index: Int, out: ByteArray): Int
    private external fun nativeReadFlash(handle: Long, out: ByteArray, offset: Int, chunkOffset: Int, chunkSize: Int): Int

    companion object {
        init {
            System.loadLibrary("espflasher")
        }

        // Order matches target_chip_t.
        private val CHIP_NAMES = listOf(
            "ESP8266", "ESP32", "ESP32-S2", "ESP32-C3", "ESP32-S3", "ESP32-C2", "ESP32-C5",
            "ESP32-H2", "ESP32-C6", "ESP32-P4", "ESP32-C61", "ESP32-S31", "ESP32-H21", "ESP32-H4",
        )

        private const val READ_CHUNK = 64 * 1024
        private const val NO_BIT = 0xFFFF

        // Order matches nativeGetSecurityInfo() in flasher_jni.c.
        private val SECURITY_FLAGS = listOf(
            "Secure boot", "Secure boot aggressive revoke", "Secure download mode",
            "Secure boot key 0 revoked", "Secure boot key 1 revoked", "Secure boot key 2 revoked",
            "Flash encryption", "JTAG disabled (software)", "JTAG disabled (hardware)", "USB disabled",
            "D-cache disabled in download mode", "I-cache disabled in download mode",
        )

        // Order matches esp_loader_error_t.
        private val ERROR_NAMES = listOf(
            "SUCCESS", "FAIL", "TIMEOUT", "IMAGE_SIZE", "INVALID_MD5", "INVALID_PARAM",
            "INVALID_TARGET", "UNSUPPORTED_CHIP", "UNSUPPORTED_FUNC", "INVALID_RESPONSE",
            "EFUSE_BLOCK_IN_USE", "EFUSE_BURN_FAILED",
        )
    }
}
