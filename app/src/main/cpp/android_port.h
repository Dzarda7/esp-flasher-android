/*
 * SPDX-FileCopyrightText: 2026 Jaroslav Burian
 * SPDX-License-Identifier: MIT
 *
 * esp-serial-flasher port for Android.
 *
 * Android apps cannot open /dev/tty* directly, so all serial I/O is forwarded
 * over JNI to a Kotlin UsbSerialBridge object, which talks to the USB-serial
 * adapter through the Android USB Host API (usb-serial-for-android).
 */

#pragma once

#include <stdbool.h>
#include <stdint.h>

#include <jni.h>

#include "esp_loader_io.h"

typedef struct {
    esp_loader_port_t port; /*!< Embedded port base — pass &port to esp_loader_init_serial() */

    /* JNIEnv of the thread currently running a loader call. Set by every JNI
     * entry point before calling into the library. */
    JNIEnv *env;
    jobject bridge;    /*!< Global ref to the Kotlin UsbSerialBridge */
    jbyteArray io_buf; /*!< Global ref, 64 KiB scratch buffer for read/write */

    jmethodID m_write;
    jmethodID m_read;
    jmethodID m_set_lines;
    jmethodID m_set_baud;
    jmethodID m_log;

    bool is_usb_jtag; /*!< Built-in USB-Serial-JTAG: re-enumerates on reset */
    int64_t time_end_ms;

    uint8_t rx[16384]; /*!< Received bytes not yet handed to the loader */
    uint16_t rx_pos;
    uint16_t rx_len;
} android_port_t;

extern const esp_loader_port_ops_t android_port_ops;

/** Resolves method IDs and creates global refs. Returns false on failure. */
bool android_port_create(android_port_t *p, JNIEnv *env, jobject bridge);

/** Releases global refs. */
void android_port_destroy(android_port_t *p, JNIEnv *env);
