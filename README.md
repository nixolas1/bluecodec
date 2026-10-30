<p align="center"><img src="docs/images/icon.svg" width="96" alt="BlueCodec heart and Bluetooth icon"></p>
<h1 align="center">BlueCodec</h1>
<p align="center">Take control of Bluetooth reconnects, volume, sound output, and audio codecs on Android.</p>
<p align="center"><a href="https://github.com/nixolas1/bluecodec/releases/latest">Download APK</a> · <a href="https://github.com/nixolas1/bluecodec/issues">Report a problem</a> · <a href="LICENSE">MIT license</a></p>
<p align="center"><a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22no.nixo.bluecodec%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fnixolas1%2Fbluecodec%22%2C%22author%22%3A%22nixolas1%22%2C%22name%22%3A%22BlueCodec%22%7D"><img src="docs/images/obtainium.png" width="180" alt="Get it on Obtainium"></a></p>

A Bluetooth audio utility built to solve reconnect, volume and output problems
on a Pixel 10 Pro. Requires **Android 12+** and
[**Shizuku**](https://shizuku.rikka.app/guide/setup/); **no root required**.

## Features

- **Reconnect control:** experimentally suppress startup reconnects while keeping
  devices paired, using BlueCodec's Bluetooth Quick Settings tile.
- **Volume recovery:** restore synchronization when Android loses the Bluetooth
  volume registration, which can leave speakers quiet or volume controls unlinked.
- **Sound output:** switch between the phone speaker and connected Bluetooth audio
  devices, even when Android's output picker gets stuck.
- **Preferred codecs:** save LDAC, aptX HD, aptX, AAC or SBC per device and reapply
  it on reconnect, when supported by both devices.

<p align="center">
  <img src="docs/images/app.png" width="300" alt="BlueCodec light theme with device, codec and sound output controls">
  <img src="docs/images/app-dark.png" width="300" alt="BlueCodec dark theme">
</p>

## Install

1. Install and start [Shizuku](https://shizuku.rikka.app/guide/setup/).
2. Install the [latest APK](https://github.com/nixolas1/bluecodec/releases/latest)
   or use the Obtainium button above.
3. Open BlueCodec and grant Nearby devices, Shizuku access and notifications.
4. Connect a device, choose a codec and enable **Apply on reconnect** if available.
   Swipe between device cards to configure each one; use **Sound output** to
   change playback routing.

**Obtainium:** add `https://github.com/nixolas1/bluecodec` as a GitHub source to
install and track release updates. No custom APK filter is currently needed.

After restarting your phone, start Shizuku again and reopen BlueCodec.

## Reconnect control

Enable **Block automatic reconnect**, tap **Add Bluetooth tile**, then use
**BlueCodec's tile** to turn Bluetooth off and on. Android's built-in tile does
not run the blocking step. Connect manually from Settings or **Connect** in the app.

A 2-second startup window blocks reconnects; afterward, headset-initiated
connections are allowed while Android's outgoing automatic reconnect policy stays
disabled. This timing and headset behavior still need physical verification.
Enabling the option alone does not disconnect current playback.

**Before uninstalling or clearing app data:** turn Bluetooth and Shizuku on,
turn **Block automatic reconnect** off, and let restoration finish. Otherwise,
headset profiles may remain blocked. Official updates can be installed in place;
locally built debug APKs use a different signing key.

## Compatibility

Tested on **Pixel 10 Pro / Android 17**, with Sony WH-1000XM3, HK Onyx Studio 6
and a Samsung soundbar. Workarounds depend on firmware; installation on another
phone does not guarantee support.

Reconnect control covers classic A2DP/HFP audio; output switching covers A2DP.
LE Audio, hearing aids and casting are outside the output control's scope.
Codec preferences cannot add unsupported codecs or force sample rate/bit depth.
Volume recovery targets a specific synchronization fault, not every low-volume
problem. If Settings cannot connect, try **Connect** in BlueCodec.

See [tested behavior and limits](docs/verification.md) and the
[device adaptation guide](docs/device-compatibility.md).

## Privacy

No Internet permission, analytics, ads or account. Device preferences stay local.
Shizuku provides Bluetooth control; the foreground notification keeps monitoring
running. The Settings connection bridge reads a filtered Bluetooth log stream
without saving its contents.

## Development and feedback

Build with JDK 17 and Android SDK 36:

```sh
./gradlew assembleDebug lintDebug assembleDebugAndroidTest
```

See [build, test and signing instructions](docs/building.md).
[Report an issue](https://github.com/nixolas1/bluecodec/issues) with your phone,
Android build, Shizuku version, audio device and steps to reproduce. Redact MAC
addresses and unrelated personal information. Compatibility fixes are welcome.

[MIT license](LICENSE). Uses [Shizuku API](https://github.com/RikkaApps/Shizuku-API)
and [Material Components](https://github.com/material-components/material-components-android).
