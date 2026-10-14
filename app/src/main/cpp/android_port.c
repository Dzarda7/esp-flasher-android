/*
 * SPDX-FileCopyrightText: 2026 Jaroslav Burian
 * SPDX-License-Identifier: MIT
 *
 * The reset sequences are adapted from linux_port.c of esp-serial-flasher
 * (Copyright Espressif Systems (Shanghai) CO LTD, Apache-2.0).
 */

#include "android_port.h"

#include <android/log.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define TAG "esp-flasher"
#define IO_BUF_SIZE 65536 /* write/read sizes are uint16_t, so this always fits */

/* Same line semantics as linux_port.c: "asserted" means the line is set, which
 * on the standard auto-reset circuit pulls the target pin LOW. */
#define DTR_BOOT_ASSERT    (!SERIAL_FLASHER_BOOT_INVERT)
#define DTR_BOOT_DEASSERT  ( SERIAL_FLASHER_BOOT_INVERT)
#define RTS_RESET_ASSERT   (!SERIAL_FLASHER_RESET_INVERT)
#define RTS_RESET_DEASSERT ( SERIAL_FLASHER_RESET_INVERT)

static int64_t time_now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void sleep_ms(uint32_t ms)
{
    usleep((useconds_t)ms * 1000u);
}

/* Kotlin side is expected to catch its own exceptions, but never let one
 * propagate back into the library. */
static bool clear_exception(JNIEnv *env)
{
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        return true;
    }
    return false;
}

static void set_lines(android_port_t *p, bool dtr, bool rts)
{
    (*p->env)->CallVoidMethod(p->env, p->bridge, p->m_set_lines, (jboolean)dtr, (jboolean)rts);
    clear_exception(p->env);
}

bool android_port_create(android_port_t *p, JNIEnv *env, jobject bridge)
{
    jclass cls = (*env)->GetObjectClass(env, bridge);
    p->port.ops       = &android_port_ops;
    p->env            = env;
    p->m_write        = (*env)->GetMethodID(env, cls, "write", "([BII)Z");
    p->m_read         = (*env)->GetMethodID(env, cls, "read", "([BII)I");
    p->m_set_lines    = (*env)->GetMethodID(env, cls, "setLines", "(ZZ)V");
    p->m_set_baud     = (*env)->GetMethodID(env, cls, "setBaudRate", "(I)Z");
    jmethodID m_is_usb_jtag = (*env)->GetMethodID(env, cls, "isUsbJtag", "()Z");
    p->m_log          = (*env)->GetMethodID(env, cls, "log", "(ILjava/lang/String;)V");
    (*env)->DeleteLocalRef(env, cls);

    if (clear_exception(env) || !p->m_write || !p->m_read || !p->m_set_lines || !p->m_set_baud
            || !m_is_usb_jtag || !p->m_log) {
        return false;
    }
    p->is_usb_jtag = (*env)->CallBooleanMethod(env, bridge, m_is_usb_jtag);
    if (clear_exception(env)) {
        return false;
    }

    jbyteArray buf = (*env)->NewByteArray(env, IO_BUF_SIZE);
    if (buf == NULL) {
        clear_exception(env);
        return false;
    }
    p->io_buf = (*env)->NewGlobalRef(env, buf);
    (*env)->DeleteLocalRef(env, buf);
    p->bridge = (*env)->NewGlobalRef(env, bridge);
    return true;
}

void android_port_destroy(android_port_t *p, JNIEnv *env)
{
    if (p->bridge) {
        (*env)->DeleteGlobalRef(env, p->bridge);
        p->bridge = NULL;
    }
    if (p->io_buf) {
        (*env)->DeleteGlobalRef(env, p->io_buf);
        p->io_buf = NULL;
    }
}

static esp_loader_error_t android_write(esp_loader_port_t *port, const uint8_t *data, uint16_t size,
                                        uint32_t timeout)
{
    android_port_t *p = container_of(port, android_port_t, port);
    JNIEnv *env = p->env;

    (*env)->SetByteArrayRegion(env, p->io_buf, 0, size, (const jbyte *)data);
    jboolean ok = (*env)->CallBooleanMethod(env, p->bridge, p->m_write, p->io_buf, (jint)size,
                                            (jint)timeout);
    if (clear_exception(env)) {
        return ESP_LOADER_ERROR_FAIL;
    }
    return ok ? ESP_LOADER_SUCCESS : ESP_LOADER_ERROR_TIMEOUT;
}

/* Reads exactly `size` bytes before the timer started by start_timer() expires,
 * matching the semantics of linux_port.c.
 *
 * The SLIP decoder reads one byte at a time, so serve those from a local buffer and refill
 * it with one JNI call for everything available. A JNI round trip per byte left the USB IN
 * endpoint unpolled for so long during a flash read that a USB-UART bridge's small RX FIFO
 * overflowed and bytes were lost. */
static esp_loader_error_t android_read(esp_loader_port_t *port, uint8_t *data, uint16_t size,
                                       uint32_t timeout)
{
    android_port_t *p = container_of(port, android_port_t, port);
    JNIEnv *env = p->env;
    (void)timeout;

    for (uint16_t got = 0; got < size;) {
        if (p->rx_pos == p->rx_len) {
            int64_t remaining = p->time_end_ms - time_now_ms();
            if (remaining <= 0) {
                return ESP_LOADER_ERROR_TIMEOUT;
            }
            jint n = (*env)->CallIntMethod(env, p->bridge, p->m_read, p->io_buf, (jint)sizeof(p->rx),
                                           (jint)remaining);
            if (clear_exception(env) || n < 0) {
                return ESP_LOADER_ERROR_FAIL;
            }
            (*env)->GetByteArrayRegion(env, p->io_buf, 0, n, (jbyte *)p->rx);
            p->rx_pos = 0;
            p->rx_len = n;
            continue;
        }
        uint16_t n = p->rx_len - p->rx_pos < size - got ? p->rx_len - p->rx_pos : size - got;
        memcpy(&data[got], &p->rx[p->rx_pos], n);
        p->rx_pos += n;
        got += n;
    }
    return ESP_LOADER_SUCCESS;
}

static void android_start_timer(esp_loader_port_t *port, uint32_t ms)
{
    android_port_t *p = container_of(port, android_port_t, port);
    p->time_end_ms = time_now_ms() + (int64_t)ms;
}

static uint32_t android_remaining_time(esp_loader_port_t *port)
{
    android_port_t *p = container_of(port, android_port_t, port);
    int64_t remaining = p->time_end_ms - time_now_ms();
    return remaining > 0 ? (uint32_t)remaining : 0;
}

static void android_delay_ms(esp_loader_port_t *port, uint32_t ms)
{
    (void)port;
    sleep_ms(ms);
}

static esp_loader_error_t android_change_rate(esp_loader_port_t *port, uint32_t baudrate)
{
    android_port_t *p = container_of(port, android_port_t, port);
    jboolean ok = (*p->env)->CallBooleanMethod(p->env, p->bridge, p->m_set_baud, (jint)baudrate);
    if (clear_exception(p->env) || !ok) {
        return ESP_LOADER_ERROR_FAIL;
    }
    return ESP_LOADER_SUCCESS;
}

/* Reset sequences mirror linux_port.c (esptool HardReset / UnixTightReset /
 * USBJTAGSerialReset). Unlike TIOCMSET, the USB API sets DTR and RTS with two
 * separate control transfers, so line changes are not atomic.
 * If the USB-Serial-JTAG drops off the bus on reset, UsbSerialBridge reopens it
 * on the next failed read/write. */
static void android_reset_target(esp_loader_port_t *port)
{
    android_port_t *p = container_of(port, android_port_t, port);

    set_lines(p, false, RTS_RESET_ASSERT);
    sleep_ms(SERIAL_FLASHER_RESET_HOLD_TIME_MS);
    set_lines(p, false, RTS_RESET_DEASSERT);
}

static void android_enter_bootloader(esp_loader_port_t *port)
{
    android_port_t *p = container_of(port, android_port_t, port);

    if (p->is_usb_jtag) {
        set_lines(p, DTR_BOOT_DEASSERT, RTS_RESET_DEASSERT); /* idle */
        sleep_ms(SERIAL_FLASHER_RESET_HOLD_TIME_MS);
        set_lines(p, DTR_BOOT_ASSERT,   RTS_RESET_DEASSERT); /* assert BOOT */
        sleep_ms(SERIAL_FLASHER_RESET_HOLD_TIME_MS);
        set_lines(p, DTR_BOOT_ASSERT,   RTS_RESET_ASSERT);   /* assert RESET */
        set_lines(p, DTR_BOOT_DEASSERT, RTS_RESET_ASSERT);   /* release BOOT via (1,1) */
        sleep_ms(SERIAL_FLASHER_RESET_HOLD_TIME_MS);
        set_lines(p, DTR_BOOT_DEASSERT, RTS_RESET_DEASSERT); /* release RESET */
    } else {
        set_lines(p, DTR_BOOT_DEASSERT, RTS_RESET_DEASSERT);
        set_lines(p, DTR_BOOT_ASSERT,   RTS_RESET_ASSERT);
        set_lines(p, DTR_BOOT_DEASSERT, RTS_RESET_ASSERT);
        sleep_ms(SERIAL_FLASHER_RESET_HOLD_TIME_MS);
        set_lines(p, DTR_BOOT_ASSERT,   RTS_RESET_DEASSERT);
        sleep_ms(SERIAL_FLASHER_BOOT_HOLD_TIME_MS);
        set_lines(p, DTR_BOOT_DEASSERT, RTS_RESET_DEASSERT);
    }
}

static void android_log(esp_loader_port_t *port, esp_loader_log_level_t level, const char *fmt,
                        va_list args)
{
    android_port_t *p = container_of(port, android_port_t, port);
    char msg[512];
    vsnprintf(msg, sizeof(msg), fmt, args);

    static const int prio[] = {
        [ESP_LOADER_LOG_LEVEL_NONE]  = ANDROID_LOG_SILENT,
        [ESP_LOADER_LOG_LEVEL_ERROR] = ANDROID_LOG_ERROR,
        [ESP_LOADER_LOG_LEVEL_WARN]  = ANDROID_LOG_WARN,
        [ESP_LOADER_LOG_LEVEL_INFO]  = ANDROID_LOG_INFO,
        [ESP_LOADER_LOG_LEVEL_DEBUG] = ANDROID_LOG_DEBUG,
    };
    __android_log_write(prio[level], TAG, msg);

    jstring jmsg = (*p->env)->NewStringUTF(p->env, msg);
    if (jmsg != NULL) {
        (*p->env)->CallVoidMethod(p->env, p->bridge, p->m_log, (jint)level, jmsg);
        (*p->env)->DeleteLocalRef(p->env, jmsg);
    }
    clear_exception(p->env);
}

const esp_loader_port_ops_t android_port_ops = {
    .enter_bootloader         = android_enter_bootloader,
    .reset_target             = android_reset_target,
    .start_timer              = android_start_timer,
    .remaining_time           = android_remaining_time,
    .delay_ms                 = android_delay_ms,
    .log                      = android_log,
    .change_transmission_rate = android_change_rate,
    .write                    = android_write,
    .read                     = android_read,
};
