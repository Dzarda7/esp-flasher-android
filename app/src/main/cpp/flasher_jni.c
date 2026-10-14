/*
 * JNI entry points for com.espflasher.Flasher.
 *
 * A session owns one port + loader. All calls on a session must be serialized
 * (the Kotlin side runs them on a single worker thread).
 */

#include <jni.h>
#include <stdlib.h>
#include "esp_loader.h"
#include "android_port.h"

#define FLASH_BLOCK_ROM  0x400
#define FLASH_BLOCK_STUB 0x4000

typedef struct {
    android_port_t port;
    esp_loader_t loader;
    bool stub_running;
} session_t;

static session_t *session_get(JNIEnv *env, jlong handle)
{
    session_t *s = (session_t *)(intptr_t)handle;
    s->port.env = env; /* the library calls back into Java from this thread */
    return s;
}

#define JNI_FN(name) Java_com_espflasher_Flasher_##name

JNIEXPORT jlong JNICALL JNI_FN(nativeCreate)(JNIEnv *env, jobject thiz, jobject bridge)
{
    (void)thiz;
    session_t *s = calloc(1, sizeof(*s));
    if (s == NULL) {
        return 0;
    }
    if (!android_port_create(&s->port, env, bridge)
            || esp_loader_init_serial(&s->loader, &s->port.port) != ESP_LOADER_SUCCESS) {
        android_port_destroy(&s->port, env);
        free(s);
        return 0;
    }
    return (jlong)(intptr_t)s;
}

JNIEXPORT void JNICALL JNI_FN(nativeDestroy)(JNIEnv *env, jobject thiz, jlong handle)
{
    (void)thiz;
    session_t *s = session_get(env, handle);
    esp_loader_deinit(&s->loader);
    android_port_destroy(&s->port, env);
    free(s);
}

JNIEXPORT jint JNICALL JNI_FN(nativeConnect)(JNIEnv *env, jobject thiz, jlong handle)
{
    (void)thiz;
    session_t *s = session_get(env, handle);
    esp_loader_connect_args_t args = ESP_LOADER_CONNECT_DEFAULT();

    esp_loader_error_t err = esp_loader_connect_with_stub(&s->loader, &args);
    s->stub_running = err == ESP_LOADER_SUCCESS;
    return err;
}

JNIEXPORT jint JNICALL JNI_FN(nativeChangeBaudRate)(JNIEnv *env, jobject thiz, jlong handle, jint baud)
{
    (void)thiz;
    session_t *s = session_get(env, handle);
    return esp_loader_change_transmission_rate(&s->loader, (uint32_t)baud);
}

JNIEXPORT jint JNICALL JNI_FN(nativeGetTarget)(JNIEnv *env, jobject thiz, jlong handle)
{
    (void)thiz;
    return esp_loader_get_target(&session_get(env, handle)->loader);
}

JNIEXPORT jint JNICALL JNI_FN(nativeReadMac)(JNIEnv *env, jobject thiz, jlong handle, jbyteArray out)
{
    (void)thiz;
    session_t *s = session_get(env, handle);
    uint8_t mac[6];
    esp_loader_error_t err = esp_loader_read_mac(&s->loader, mac);
    if (err == ESP_LOADER_SUCCESS) {
        (*env)->SetByteArrayRegion(env, out, 0, sizeof(mac), (const jbyte *)mac);
    }
    return err;
}

JNIEXPORT jint JNICALL JNI_FN(nativeDetectFlashSize)(JNIEnv *env, jobject thiz, jlong handle, jintArray out)
{
    (void)thiz;
    session_t *s = session_get(env, handle);
    uint32_t size = 0;
    esp_loader_error_t err = esp_loader_flash_detect_size(&s->loader, &size);
    if (err == ESP_LOADER_SUCCESS) {
        jint v = (jint)size;
        (*env)->SetIntArrayRegion(env, out, 0, 1, &v);
    }
    return err;
}

JNIEXPORT jint JNICALL JNI_FN(nativeFlash)(JNIEnv *env, jobject thiz, jlong handle, jbyteArray data,
                                           jint offset, jobject listener)
{
    (void)thiz;
    session_t *s = session_get(env, handle);

    jclass cls = (*env)->GetObjectClass(env, listener);
    jmethodID on_progress = (*env)->GetMethodID(env, cls, "onProgress", "(II)V");
    (*env)->DeleteLocalRef(env, cls);
    if (on_progress == NULL) {
        (*env)->ExceptionClear(env);
        return ESP_LOADER_ERROR_INVALID_PARAM;
    }

    const uint32_t total = (uint32_t)(*env)->GetArrayLength(env, data);
    const uint32_t block_size = s->stub_running ? FLASH_BLOCK_STUB : FLASH_BLOCK_ROM;
    uint8_t *block = malloc(block_size);
    if (block == NULL) {
        return ESP_LOADER_ERROR_FAIL;
    }

    esp_loader_flash_cfg_t cfg = {
        .offset = (uint32_t)offset,
        .image_size = total,
        .block_size = block_size,
    };
    esp_loader_error_t err = esp_loader_flash_start(&s->loader, &cfg);

    uint32_t written = 0;
    while (err == ESP_LOADER_SUCCESS && written < total) {
        uint32_t len = total - written < block_size ? total - written : block_size;
        (*env)->GetByteArrayRegion(env, data, (jsize)written, (jsize)len, (jbyte *)block);
        err = esp_loader_flash_write(&s->loader, &cfg, block, len);
        if (err == ESP_LOADER_SUCCESS) {
            written += len;
            (*env)->CallVoidMethod(env, listener, on_progress, (jint)written, (jint)total);
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionClear(env);
            }
        }
    }
    free(block);

    if (err == ESP_LOADER_SUCCESS) {
        err = esp_loader_flash_finish(&s->loader, &cfg);
    }
    return err;
}

JNIEXPORT void JNICALL JNI_FN(nativeReset)(JNIEnv *env, jobject thiz, jlong handle)
{
    (void)thiz;
    esp_loader_reset_target(&session_get(env, handle)->loader);
}
