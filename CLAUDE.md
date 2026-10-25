# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

OpenPrintSense / SmartGlove: a 3D-printed flex sensor (TPU + conductive filament) on a glove, read by a Seeed XIAO nRF52840 Sense, streamed over BLE to an Android app. Two independent codebases share one BLE protocol:

- `nRF52840/nRF52840.ino` — Arduino firmware (single sketch, Adafruit Bluefruit on the Nordic SoftDevice)
- `AndroidApp/` — Kotlin Android app (view binding, minSdk 26, targetSdk 36, compileSdk 37)
- `CAD Files/OpenPrintSense.step` — sensor/glove model

Code comments, log messages and serial output are in German; keep that style when editing existing files.

## Commands

Android (run from `AndroidApp/`; use `gradlew.bat` in PowerShell, `./gradlew` in Bash):

```
./gradlew assembleDebug     # build APK -> app/build/outputs/apk/debug/
./gradlew installDebug      # install on a connected device
./gradlew lint
```

Toolchain: Gradle 9.8.2, AGP 9.4.1, Kotlin 2.4.21, JDK 17+ (the JDK 25 bundled with Android Studio works). AGP 9 has built-in Kotlin support. The app module must therefore **not** apply `org.jetbrains.kotlin.android`; that plugin is only declared with `apply false` in the root `build.gradle.kts` to raise the Kotlin version above AGP's bundled 2.2.10. Kotlin compiler options go in the top-level `kotlin { compilerOptions { … } }` block, not `android { kotlinOptions }`. Keep minSdk at 26 to support older phones. Current AndroidX libraries need only API 23, but going lower than 26 would also need raster launcher icons, since the icons exist only in `mipmap-anydpi-v26`.

There are no unit or instrumentation tests. BLE behaviour can only be verified on a physical device with the glove. `release` references `proguard-rules.pro`, which doesn't exist; this only matters if `isMinifyEnabled` is turned on.

Firmware is built and flashed with the Arduino IDE using the **"Seeed nRF52 Boards"** package, board `Seeed XIAO nRF52840 Sense`; the board URL is `https://files.seeedstudio.com/arduino/package_seeeduino_boards_index.json`. Do **not** use "Seeed nRF52 mbed-enabled Boards" (its boards are labelled "(No Updates)"); see the security section for why. It also compiles headless with the arduino-cli bundled in Arduino IDE:

```
arduino-cli compile --fqbn Seeeduino:nrf52:xiaonRF52840Sense nRF52840
arduino-cli upload  --fqbn Seeeduino:nrf52:xiaonRF52840Sense -p COMx nRF52840
```

If an upload fails, double-tap RESET to enter the bootloader. Serial output runs at 115200 baud and only appears when the terminal asserts DTR (TinyUSB); with arduino-cli use `monitor -c baudrate=115200,dtr=on`. When stdin is not a terminal, `arduino-cli monitor` exits immediately, so pipe something like `sleep N |` into it for unattended captures.

## Architecture

**App structure.** `MainActivity` owns all BLE and calibration state and hosts two tabs (TabLayout + ViewPager2 via `ViewPagerAdapter`): `ControlFragment` (connect, live values, calibration) and `GameFragment`. Fragments have no BLE code of their own. They cast `activity as MainActivity`, read and write its public state (`minValue`, `maxValue`, `currentRawValue`, `calibrationStep`, `isCalibrated`, `prefs`), call `checkPermissionsAndScan()` / `disconnect()` / `sendCalibrationValue(isMin)`, and register in `onResume`/`onPause` as a `GloveDataListener` (an interface declared at the top of `MainActivity.kt`). `MainActivity` sends every BLE event to all registered listeners on the main thread. With targetSdk ≥ 35 the app draws edge-to-edge (`enableEdgeToEdge()`), and `MainActivity` pads the root view by the system-bar and display-cutout insets. Theme `statusBarColor`/`navigationBarColor` have no effect on Android 15+.

`MainActivity.kt` was reconstructed in Oct 2026 from decompiled build output, after the original source was overwritten before the first commit.

**BLE protocol (keep both sides in sync).** Service `19B10000-E8F2-537E-4F6C-D104768A1214`. Its characteristics `19B10001`–`19B10006` (stretch %, raw ADC, device ID string, min cal, max cal, calibrated bool) are declared in the firmware's globals and again in `MainActivity`'s `companion object`. Integers are 32-bit little-endian. The device advertises the service UUID, with name `SmartGlove-<DEVICE_ID>` in the scan response. Set `DEVICE_ID` per physical glove in the sketch.

**Pairing / security.** Every characteristic has permission `SECMODE_ENC_WITH_MITM`. The SoftDevice enforces it for reads, writes and CCCD writes, since Bluefruit gives the CCCD the characteristic's read permission. Pairing is passkey entry with the static `PAIRING_PIN` (`Bluefruit.Security.setPIN`). The PIN comes from the git-ignored `nRF52840/secrets.h` via `__has_include`, with fallback `123456` plus a compiler `#warning`. Never put a real PIN in the tracked sketch. Bluefruit stores bonds itself in InternalFS (LittleFS). The firmware sends sensor data only while `connection->secured()`, and disconnects unsecured centrals after `UNPAIRED_TIMEOUT_MS`. Serial command `CLEAR` calls `Bluefruit.Periph.clearBonds()`. 128-bit UUIDs are written as little-endian byte arrays via the `GLOVE_UUID(x)` macro.

The firmware used to run on ArduinoBLE with the mbed core. ArduinoBLE's LE Secure Connections pairing finishes there: both DHKey checks pass, and the LTK is handed to the controller. The Cordio controller then disconnects with MIC failure (0x3D), and the phone only sees a supervision timeout. This was reproduced with both LTK byte orders, and ArduinoBLE's own source marks Nano BLE (Cordio) pairing as unconfirmed. Don't move back to ArduinoBLE/mbed if encryption is needed.

On the app side, the scan is filtered by service UUID. `connectToDevice` calls `createBond()` first if needed, and Android then shows the PIN dialog. The app waits for `ACTION_BOND_STATE_CHANGED` in `bondReceiver`, which must be registered `RECEIVER_EXPORTED`: the broadcast comes from the Bluetooth process (its own UID), so a `NOT_EXPORTED` receiver never gets it. `MainActivity.onResume` also re-checks `bondState` as a fallback, because the pairing dialog pauses the activity. Status messages go through `showStatus(key)`, so `ControlFragment` can restore the last one (`lastStatus`) after `onResume`. After the first successful pairing, the app only accepts gloves in `trustedGloves()`: addresses saved in prefs that Android still lists as bonded. Removing the glove in Android's Bluetooth settings therefore resets trust. On Android 12+ the app requests only `BLUETOOTH_SCAN` (`neverForLocation`) and `BLUETOOTH_CONNECT`; location permissions are `maxSdkVersion="30"`.

**Firmware loop.** Sensor work happens only while a secured central is connected. Every ~50 ms the loop reads `A0` (10 bit, `AR_VDD4` reference so the reading is ratiometric to the 3.3 V divider) and maps it from `[minWert, maxWert]` to 0–100 (clamped). It then calls `notify32` for stretch and raw; without enabled notifications, that only updates the readable value. Calibration is written in write callbacks (`minCalWritten`/`maxCalWritten`). Values outside 0–1023 are rejected, and the characteristic is reset to the current value. Writing max cal sets `isCalibrated`. Calibration lives only in RAM and is lost on reset or power loss.

**Calibration flow.** Calibration is driven by the app. The user bends the finger fully and presses "Set Min", then extends it fully and presses "Set Max". Each button captures the latest raw notification value and writes it to the matching characteristic. Because of that mapping, 0% = fully bent and 100% = fully extended. The `calibrationStep` state machine runs 0 = idle, 1 = waiting for min, 2 = waiting for max, 3 = done.

**Android GATT sequencing.** Android allows only one GATT operation at a time, so setup runs as a chain: `onServicesDiscovered` enables raw notifications, then `onDescriptorWrite` enables stretch notifications, then it reads the device ID. Any new subscription needs to be added to that chain rather than fired in parallel. Integers are decoded little-endian by `parseIntValue`. GATT callbacks run off the main thread; listener calls go through `handler.post`. `disconnect()` only calls `gatt.disconnect()`. On `STATE_DISCONNECTED`, the main thread closes the GATT client and clears `bluetoothGatt` and the cached characteristics.

**Localization.** UI strings aren't in `res/values/strings.xml`. They're in a `strings` map in `MainActivity` of `key to Pair(german, english)`, for both tabs, looked up with `getString(key)` (which shadows `Context.getString`). The language (`"de"` default / `"en"`) is stored in SharedPreferences `SmartGlovePrefs`. Tapping the flag icon (`ivLanguage`) switches it and calls `onLanguageChanged()` on all listeners. New UI text needs an entry in that map.

**Game tab.** `GameFragment` steers a spaceship with the stretch value, smoothed with an exponential filter (`smoothingFactor`), to dodge comets and collect stars. It also stores a highscore.
