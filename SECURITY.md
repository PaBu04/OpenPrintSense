# Security Policy

## Reporting a vulnerability

Please **do not report security vulnerabilities through public GitHub issues.**

Report them privately instead:

- by e-mail to **kontakt@paulbuda.de**, or
- via GitHub Private Vulnerability Reporting: <https://github.com/PaBu04/OpenPrintSense/security/advisories/new>

Please include a description of the issue, the affected component and version (commit), and steps to reproduce it.

OpenPrintSense is a private, non-commercial hobby project. Reports are answered on a best-effort basis, and there is no guaranteed response time. There is **no bug bounty**.

## Scope

In scope:

- Firmware (`nRF52840/`)
- Android app (`AndroidApp/`)

Out of scope: the 3D models, and vulnerabilities in third-party libraries or toolchains (please report those upstream). Only the latest version on the default branch is supported.

## Security model

- All BLE characteristics of the glove require an encrypted connection that was paired with a PIN (LE Secure Connections, passkey entry with MITM protection, enforced by the Nordic SoftDevice). Without pairing, sensor data can't be read and calibration can't be written.
- The app only connects to gloves it has paired with before.
- The app has no internet permission, so it can't send data off the phone.

## Known limitations

- **Older firmware has no encryption.** Firmware versions before the switch to Adafruit Bluefruit (commit `d6b8d62`) used no pairing or encryption. With those versions, anyone within radio range can read the sensor data and change the calibration. Please update.
- **Default PIN.** If no `nRF52840/secrets.h` is provided, the firmware uses the publicly known PIN `123456`. Set your own PIN before flashing.
- **Static PIN.** The pairing PIN is fixed. Someone who takes part in or records pairing attempts can recover a fixed passkey over time, so treat the PIN as protection against casual pairing, not against a determined attacker nearby. Pair in a trusted environment.
- **Trackable advertising.** The glove advertises a fixed name (`SmartGlove-<DEVICE_ID>`) and a fixed address, so it can be recognised by anyone within radio range.
- **Connection blocking.** The glove accepts one connection at a time. An unpaired device can occupy it for up to 20 seconds before it is dropped, and can repeat this.
- **Physical access.** Firmware updates over USB are not signed. Anyone with physical access to the glove can flash their own firmware.
