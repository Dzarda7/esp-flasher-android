cmake_minimum_required(VERSION 3.22)
project(espflasher C)

if(NOT DEFINED ESP_SERIAL_FLASHER_DIR)
    message(FATAL_ERROR "ESP_SERIAL_FLASHER_DIR not set (see espSerialFlasherDir in gradle.properties)")
endif()

# The port lives in this project, so build the library with a user-defined port.
set(PORT USER_DEFINED)
set(SERIAL_FLASHER_LOG_LEVEL INFO)
add_subdirectory(${ESP_SERIAL_FLASHER_DIR} esp-serial-flasher)

add_library(espflasher SHARED
    android_port.c
    flasher_jni.c
)

target_link_libraries(espflasher PRIVATE flasher log)
