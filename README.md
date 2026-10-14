# ESP Flasher (Android)

Android app for flashing Espressif chips over USB OTG, built on
[esp-serial-flasher](https://github.com/espressif/esp-serial-flasher).

## Layout

```
app/src/main/
├── cpp/
│   ├── CMakeLists.txt     builds esp-serial-flasher + the JNI library
│   ├── android_port.c     esp-serial-flasher port: forwards I/O to Kotlin over JNI
│   └── flasher_jni.c      JNI entry points (connect, read MAC, flash, ...)
└── java/com/espflasher/
    ├── UsbSerialBridge.kt USB serial transport (usb-serial-for-android)
    ├── NativeFlasher.kt   JNI bindings + FlasherSession wrapper
    ├── FlasherViewModel.kt
    └── MainActivity.kt    Compose UI
```

## Building

Requires Android Studio (bundles the JDK, Android SDK and Gradle). Install the
**NDK** and **CMake 3.22.1** via *Settings → Android SDK → SDK Tools*.

The library is taken from a local checkout, by default `../../Espressif/esp-serial-flasher`
relative to this repo. Change `espSerialFlasherDir` in `gradle.properties` to point elsewhere.

Open the folder in Android Studio and press Run, or from the command line:

```sh
./gradlew installDebug
```

## Supported adapters

CP210x, CH340/CH9102, FTDI, and the built-in USB-Serial-JTAG of ESP32-C3/S3/C6/H2/P4/...
(see `res/xml/device_filter.xml`).
