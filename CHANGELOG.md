## v0.1.0 (2026-10-14)

### Feat

- **flash**: flash several binaries in one go
- **ui**: swipe between Flash, Device, eFuse and Monitor
- **ui**: dynamic colors, start screen and chip card
- **efuse**: auto-read, "Set only" filter, field details and burning
- add app icon
- **efuse**: add read-only eFuse viewer
- **device**: add Device page; connect robustly to boot-looping chips
- **ui**: replace always-visible log with a status line
- **monitor**: add serial monitor and selectable flash baud rate
- add Android app for flashing Espressif chips

### Fix

- handle unplugging, decode monitor output as a UTF-8 stream
- **usb**: make USB-UART bridges reliable on Android

### Refactor

- rename package to io.github.dzarda7.espflasher
- **ui**: split into Flash and Monitor pages with a compact header
