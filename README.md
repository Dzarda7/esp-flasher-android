# ESP Flasher (Android)

Android app for flashing and inspecting Espressif chips over USB OTG, built on
[esp-serial-flasher](https://github.com/espressif/esp-serial-flasher), the same C library
that runs on ESP32, STM32, Raspberry Pi Pico and Linux hosts.

## Features

- **Flash**: one or more binaries at their offsets (offsets are guessed from ESP-IDF file
  names: bootloader, partition table, app, or a merged image), selectable baud rate up to
  2 Mbaud, MD5 verification, throughput shown after each run
- **Monitor**: serial output of the running firmware with ESP-IDF log colors, reset, and
  sending input; starts automatically after flashing
- **Device**: chip revision, security info (secure boot, flash encryption, …), read a flash
  range to a file, erase the whole flash
- **eFuse**: every field of the chip, grouped by block, with search, a "set only" filter,
  write/read-protection markers and an `espefuse summary`-like copy. A field can be burned
  after an old → new preview, a warning for fuses that can lock the chip, and typing its
  name to confirm. **Burning eFuses is permanent.**

## Supported hardware

- **Chips**: whatever esp-serial-flasher supports (ESP8266, ESP32, ESP32-S2/S3, C2/C3/C5/C6/C61,
  H2/H21/H4, P4, S31). Tested on ESP32-C5.
- **USB**: the built-in USB-Serial-JTAG of newer chips, and USB-UART bridges
  (CP210x, CH34x, FTDI) with the usual DTR/RTS auto-reset circuit. Tested with the
  USB-Serial-JTAG and a CP2102N.
- **Phone**: Android 8.0 or newer with USB host (OTG) support. Tested on a Pixel 10a.

Chips in secure download mode are not supported.

## Building

Requires Android Studio (it bundles the JDK, the Android SDK and Gradle). The NDK and CMake
are installed by Gradle on the first build.

The library is taken from a local checkout of esp-serial-flasher, by default
`../../Espressif/esp-serial-flasher` relative to this repo. Change `espSerialFlasherDir` in
`gradle.properties` to point elsewhere.

Open the folder in Android Studio and press Run, or from the command line:

```sh
./gradlew installDebug
```

The phone's USB port is needed for the board, so connect `adb` over Wi-Fi
(*Developer options → Wireless debugging*).

## How it works

```
app/src/main/
├── cpp/
│   ├── CMakeLists.txt     builds esp-serial-flasher and the JNI library
│   ├── android_port.c     esp-serial-flasher port: I/O, reset sequences and timing via JNI
│   └── flasher_jni.c      JNI entry points (connect, flash, read, eFuse, ...)
└── java/com/espflasher/
    ├── UsbSerialBridge.kt USB transport on usb-serial-for-android
    ├── Flasher.kt         one loader session: Kotlin API over the JNI functions
    ├── FlasherViewModel.kt
    └── MainActivity.kt    Compose UI
```

Android apps can't open `/dev/tty*`, so the port forwards serial I/O to Kotlin, which talks
to the adapter through the Android USB host API using
[usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android).

Two things differ from a desktop host:

- **USB-UART bridges** get their writes paced to the UART baud rate, and several USB read
  requests are kept queued at all times. A CP2102N on a Pixel otherwise NAKs while its TX
  FIFO is full until it stops responding, and its small RX FIFO overflows when the phone
  isn't polling. See `UsbSerialBridge.write()`.
- **USB-Serial-JTAG** devices may re-enumerate when the chip resets; the bridge reopens them.
  Choose "always open with ESP Flasher" when Android asks, so this needs no new permission
  prompt.

## License

Apache License 2.0, see [LICENSE](LICENSE). esp-serial-flasher is Apache-2.0 and
usb-serial-for-android is MIT.
