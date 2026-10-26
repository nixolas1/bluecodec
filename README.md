<p align="center"><img src="docs/images/icon.svg" width="96" alt="BlueCodec heart and Bluetooth icon"></p>
<h1 align="center">BlueCodec</h1>
<p align="center">Your preferred Bluetooth codec. Your choice of when to connect.</p>
<p align="center"><a href="https://github.com/nixolas1/bluecodec/releases/latest">Download APK</a> · <a href="https://github.com/nixolas1/bluecodec/issues">Report a problem</a> · <a href="LICENSE">MIT license</a></p>

BlueCodec remembers the audio codec you choose for each headset and reapplies it
when that headset connects. It also adds a Bluetooth Quick Settings tile that can
prevent headsets from reconnecting automatically after you switch Bluetooth off
and on. Uses [Shizuku](https://shizuku.rikka.app/); no root required.

**Early release.** Tested on Pixel 10 Pro / Android 17 with Sony WH-1000XM3.
Android 12+ can install the app, but Bluetooth controls depend on your phone's
firmware. Other devices are not yet verified.

<p align="center"><img src="docs/images/app.png" width="320" alt="BlueCodec showing codec choices and the automatic reconnect toggle"></p>

## Features

- **A codec preference for each headset.** Choose from the codecs Android reports
  as selectable for that connection. Automatically reapply your choice, or apply it manually.
- **Optional automatic reconnect blocking.** Switch Bluetooth off with the
  BlueCodec tile, then turn it on without nearby headsets immediately taking over.
- **Connect through Android Settings.** On the tested Pixel, selecting a headset
  unblocks only that device. Simply opening the list does not unblock devices.
- **Bluetooth Quick Settings tile.** Tap to switch Bluetooth on/off; long press
  opens Android's connected-device settings. The wide tile shows the connected
  device's name and battery, or a count when several devices are connected.
- **Android device names.** Uses the names you've given your headsets, with stable,
  locally generated avatars and colors.
- **Light and dark themes.** Rounded Material components, clear status, and no ads.

## Install and set up

1. Install and start [Shizuku](https://shizuku.rikka.app/guide/setup/).
2. Download the APK from [Releases](https://github.com/nixolas1/bluecodec/releases/latest)
   and install it. Android may ask you to allow installation from your browser or file manager.
3. Open BlueCodec. Grant **Nearby devices**, allow Shizuku access, and allow the
   monitoring notification when prompted.
4. Connect a headset. Select a **Preferred codec** and enable **Automatic switching**.

Only codecs supported by the current phone/headset connection appear. Saving LDAC,
for example, cannot add LDAC support to headphones that don't have it. Android still
negotiates sample rate and bit depth and may fall back to another codec.

## Stop automatic reconnect

**Experimental · Tested on Pixel 10 Pro / Android 17.** This feature depends on
firmware-specific Bluetooth behavior. For other devices, see the
[device adaptation guide](docs/device-compatibility.md) to investigate compatibility,
implement a device-specific adapter, and build your own APK.

1. Enable **Disable automatic reconnect** in BlueCodec.
2. Tap **Add Bluetooth tile** and accept Android's prompt. On Android 12, add it
   manually through the Quick Settings editor.
3. Use the **Bluetooth tile from BlueCodec** to switch Bluetooth off and on.
4. When you want to connect, long press the tile and select your headset in Android
   Settings. You can also use **Connect** in BlueCodec.

The toggle does not disconnect your current headset. It changes what happens the
next time you switch Bluetooth off with BlueCodec's tile. Android's built-in
Bluetooth-off switch does not run the blocking step.

With **Disable automatic reconnect** off, the tile is a normal Bluetooth power
switch. Turning the setting off restores the connection settings BlueCodec saved;
nearby headsets may then reconnect. If Bluetooth is off, restoration waits until
it is on again.

**Before uninstalling or clearing BlueCodec's data:** turn Bluetooth and Shizuku
on, turn **Disable automatic reconnect** off, and let restoration finish. Otherwise,
saved headset profiles can remain blocked after the app is removed.

## Quick Settings sizes

Android controls the tile's size and appearance. On the tested Pixel:

| Size / connection | Display |
| --- | --- |
| Compact 1×1 | Bluetooth icon |
| Wide 2×1, one device | Saved device name, then battery percentage when available |
| Wide 2×1, multiple devices | Bluetooth, then “2 devices” (or the actual count) |
| No connected devices | Bluetooth, then On or Off |

Unknown battery levels show **Connected** instead of an invented percentage.
Android does not expose its built-in Bluetooth tile's split background or separate
icon/text click targets to app tiles.

## Compatibility and recovery

- **Shizuku must be running.** After a phone restart, start Shizuku again and open
  BlueCodec. Force-stopping the app also requires reopening it to resume monitoring.
- **Reconnect blocking covers classic audio:** A2DP and HFP. It does not cover
  LE Audio, watches, keyboards, or every Bluetooth profile.
- **Android Settings connections use a compatibility bridge.** BlueCodec reads
  the Bluetooth service's live, explicit connection requests through Shizuku. It
  verifies the Settings/SystemUI caller and opens only the selected device. This
  depends on firmware log behavior and is currently verified on the Pixel above.
- **If Settings cannot connect:** keep Shizuku running and try **Connect** in
  BlueCodec. To return to normal Android behavior, turn **Disable automatic
  reconnect** off with Bluetooth on.
- **Codec changes are bounded.** BlueCodec verifies the negotiated result and
  stops retrying if the phone/headset rejects the preference.

See [verification and test limits](docs/verification.md) for what was actually tested.

## Privacy and permissions

BlueCodec has **no Internet permission, analytics, ads, or account**. Device
identifiers and preferences stay in app-private storage. It does not remove
pairings or change firmware.

| Access | Purpose |
| --- | --- |
| Nearby devices | Read Bluetooth state and receive connection events |
| Shizuku | Read/apply codecs, manage classic-audio connection policies, and switch Bluetooth power |
| Foreground service / notifications | Keep automatic codec switching and manual connection handling running |
| Boot completed | Offer a reminder to resume after restart |

The connection-request bridge reads only a filtered Bluetooth log stream, keeps a
small in-memory queue, and does not save log contents. The monitoring notification
does not create a launcher notification dot.

## Build

Requires **JDK 17** and **Android SDK 36**. Set `ANDROID_HOME` or add `sdk.dir` to an
untracked `local.properties` file.

```sh
./gradlew assembleDebug lintDebug assembleDebugAndroidTest
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

Instrumentation tests use fake Bluetooth services and isolated preferences:

```sh
adb -s <test-device> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <test-device> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <test-device> shell am instrument -w \
  no.nixo.bluecodec.test/androidx.test.runner.AndroidJUnitRunner
```

To build a signed release, set `BLUECODEC_SIGNING_PROPERTIES` to a private properties
file containing `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`, then run:

```sh
./gradlew assembleRelease lintRelease
```

Keep signing keys and passwords outside the repository. Without signing properties,
Gradle creates an unsigned release APK. Official releases use a dedicated signing
key; they cannot update a locally debug-signed build. Restore connection settings
before uninstalling a development build to switch to the release APK.

## Contributing

Bug reports are especially useful from other phones. Include phone model, Android
version, Shizuku version, headset model, and steps to reproduce. Please redact MAC
addresses and unrelated personal information from diagnostics.

Code changes and compatibility fixes are welcome. Start with the
[device adaptation guide](docs/device-compatibility.md) for reconnect support. The main components are
`CodecService` (Shizuku access), `CodecController` (serialized operations and retries),
`ReconnectSettings` (connection policies and restoration), and
`ManualConnectionRequests` (Android Settings compatibility).

## License

[MIT](LICENSE). Built with [Shizuku API](https://github.com/RikkaApps/Shizuku-API)
and [Material Components for Android](https://github.com/material-components/material-components-android).
