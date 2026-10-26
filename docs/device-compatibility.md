# Adapting reconnect control to another device

Automatic reconnect control is **experimental**. The verified configuration is
Pixel 10 Pro, Android 17, Shizuku 13.6.0 in ADB/shell mode, and Sony WH-1000XM3.
There is no hardcoded Sony address or model check. Compatibility depends mainly
on the installed Android Bluetooth stack and how Settings requests connections.

Installing successfully on Android 12+ does not establish that reconnect control
works. This guide is for developers building and testing a fork. There is no
universal package-name or regex change that makes every device compatible.

## What needs to work

| Layer | Owning source | Check on your device |
| --- | --- | --- |
| Bluetooth access through Shizuku | [CodecService.java](../app/src/main/java/no/nixo/bluecodec/CodecService.java) | Framework initialization, profile proxies, attribution, and shell power commands |
| Classic audio policies | [AudioPolicyAccess.java](../app/src/main/java/no/nixo/bluecodec/AudioPolicyAccess.java) | Typed A2DP/HFP interfaces, method signatures, constants, actual policy readback |
| Blocking and restoration | [ReconnectSettings.java](../app/src/main/java/no/nixo/bluecodec/ReconnectSettings.java) | Journal survives process loss, block-before-off ordering, restoration preserves external changes |
| Manual Settings connections | [ManualConnectionRequests.java](../app/src/main/java/no/nixo/bluecodec/ManualConnectionRequests.java) | Fresh explicit request signal, device identity, authenticated producer and caller |
| Scheduling and lifetime | [CodecController.java](../app/src/main/java/no/nixo/bluecodec/CodecController.java), [MonitorService.java](../app/src/main/java/no/nixo/bluecodec/MonitorService.java) | Serialized work, foreground service, restart recovery |

Codec selection is a separate feature. Failure of this experimental reconnect
mechanism does not by itself mean codec selection is unsupported.

## 1. Prepare a development build

Fork/clone the repository, install JDK 17 and Android SDK 36, and start Shizuku on
your test phone. Enable USB or wireless debugging and identify the intended device:

```sh
adb devices
./gradlew assembleDebug lintDebug assembleDebugAndroidTest
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

The GitHub release and your debug build have different signing keys. Android will
not install one over the other. Before uninstalling an existing BlueCodec build,
keep Bluetooth and Shizuku on, switch **Disable automatic reconnect** off, and
wait until any restoration warning disappears. Do not clear app data while it
still owns blocked profiles: that deletes the information needed to restore them.
Do not run two forks that manage the same profiles simultaneously.

Record phone model, Android version/build, Shizuku version/mode, headset model,
and whether it uses classic audio or LE Audio. Keep raw diagnostics local and
redact addresses before attaching them to a public issue.

## 2. Verify policy access and shutdown

Start with classic A2DP/HFP audio. LE Audio is not implemented by this feature.
Inspect the installed firmware or matching vendor/AOSP source when reflection
fails; current AOSP `main` is a reference, not the contract for your phone.

`AudioPolicyAccess.invoke()` currently resolves `getService()`,
`mAttributionSource`, `IBluetoothA2dp`, and `IBluetoothHeadset`. It calls typed
methods with the device and attribution source, plus a policy for writes. If your
firmware changes these interfaces, add an explicit implementation for that actual
signature. Do not guess raw Binder transaction numbers or treat failed reads as
successful writes.

Policy constants are read from the device's framework. The current algorithm:

1. Saves original eligible policies synchronously before any write.
2. Sets owned profiles to FORBIDDEN and verifies the complete readback.
3. Only then requests Bluetooth OFF through a fixed platform shell command.
4. Confirms the adapter actually reached OFF.
5. Keeps other devices blocked when Bluetooth returns; an explicit selection
   opens only the selected device's owned profiles and requests its connection.

Check your phone's supported power commands with:

```sh
adb -s <serial> shell cmd bluetooth_manager help
```

If changing `CodecService.setBluetoothPower()`, retain bounded execution, process
cleanup, and actual adapter-state confirmation. Never report success only because
a command returned exit code zero. On the tested Pixel, `BluetoothAdapter.disable()`
failed attribution checks in the shell service; the fixed `cmd bluetooth_manager`
entry point worked.

Do not replace FORBIDDEN with UNKNOWN everywhere. UNKNOWN allowed the Sony to
reconnect. Do not wait for STATE_TURNING_OFF to write policies: that broadcast
arrived too late in physical testing. Setting FORBIDDEN can disconnect active
audio, which is why the write is performed as part of the requested shutdown.

## 3. Find an explicit manual connection signal

Android does not expose a public callback for another app's Settings Connect tap.
The current compatibility bridge observes the Bluetooth service's log message
before Android filters out blocked profiles. Capture only that tag initially:

```sh
adb -s <serial> logcat -b main -v threadtime -v uid -v epoch \
  'BluetoothAdapterServiceBinder:I' '*:S'
```

Tap a saved headset in Android Settings. The tested firmware emits a line shaped
like this (illustrative identifiers, not a real device):

```text
1793000000.123  1002 1234 1235 I BluetoothAdapterServiceBinder: connectAllEnabledProfiles: device=XX:XX:XX:XX:12:34, from uid/pid=1000/5678
```

Distinguish the **log producer's** UID/PID from the **Binder caller's** UID/PID in
the message. Confirm which processes own them. For each observed PID, inspect:

```sh
adb -s <serial> shell cat /proc/<pid>/cmdline
adb -s <serial> shell cat /proc/<pid>/status
adb -s <serial> shell pm list packages -U
```

Package names are resolved by PackageManager, not by hardcoded UID numbers.
Currently trusted system packages are `com.android.bluetooth` or
`com.google.android.bluetooth` for the producer, and `com.android.settings` or
`com.android.systemui` for the caller. Both process names and all `/proc` UID fields
must match. If your OEM uses different packages/processes, verify them against the
installed system packages before adding an explicit mapping. Do not accept all
processes running as system UID or trust a log tag alone.

If the tag/message differs, find its equivalent in your firmware's Bluetooth
service source and collect a narrowly scoped sample. Demonstrate that it identifies
an explicit user connection request—not startup reconnection, a discovery event,
or merely opening Settings. Compare manual taps against idle startup and incoming
headset connections. If no trustworthy signal exists, this implementation cannot
provide transparent Settings connections on that device; do not manufacture one
with an arbitrary timer that unblocks every headset.

## 4. Implement the device-specific parser

In `ManualConnectionRequests`:

- Update both the logcat filter in `start()` and the exact message parser `LINE`.
  Prefer separate named parsers for distinct firmware formats over one permissive regex.
- Keep timestamp normalization explicit. The existing parser expects epoch seconds
  convertible exactly to milliseconds; another precision needs its own tested conversion.
- Preserve producer and caller authentication in `identity()` and `trust()`.
- Resolve an address to exactly one bonded device in `resolve()`. A redacted suffix
  is accepted only when unique. Never pick the first match on ambiguity.
- Preserve the live-start cutoff, five-second expiry, bounded queue, and cleanup.
  Reading old log entries can replay a connection the user no longer wants.
- Keep the controller's enabled-setting and journal-ownership checks. A valid log
  must not change unrelated or previously user-disabled profiles.

A running logcat process only establishes that the reader started. The current
`available` flag does **not** prove your firmware emits compatible events; an
unsupported format can remain silent. Physical manual-connection testing is required.
Keep persistent diagnostics out of release builds.

If changing shell-service behavior, increment `Shizuku.UserServiceArgs.version()`
in `CodecController` so installation does not reuse an old running implementation.
If changing AIDL, append a new explicit transaction ID and update fake services in
tests; do not renumber existing methods.

## 5. Test before claiming support

Add redacted parser fixtures to `ManualConnectionRequestsTest`: valid requests,
wrong tag/caller formats, malformed timestamps, stale/future events, unknown devices,
and ambiguous suffixes. Extend `ReconnectSettingsTest` for any policy changes.
These tests use fakes and isolated preferences; they do not prove real Bluetooth behavior.

```sh
./gradlew assembleDebug assembleDebugAndroidTest lintDebug
adb -s <test-device> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <test-device> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <test-device> shell am instrument -w \
  no.nixo.bluecodec.test/androidx.test.runner.AndroidJUnitRunner
```

Then verify on physical hardware:

- Enabling the setting alone leaves current playback connected.
- Tile OFF blocks owned profiles before shutdown; ON leaves headsets disconnected.
  Observe long enough to cover your headset's own reconnect attempts, not just one snapshot.
- Opening Android Settings alone does not unblock anything.
- Selecting one headset connects that headset; other managed headsets stay blocked.
  Test Settings and the system Bluetooth picker separately if claiming both.
- Unknown/ambiguous requests never connect a different device.
- Turning the feature off restores original policies, including after app-process
  restart, interrupted writes, or temporarily unavailable Shizuku.
- With Bluetooth off, a pending restore completes after Bluetooth and Shizuku return.
- The tile still works as a normal power toggle with reconnect control disabled.
- Codec preferences, manual disconnect, and subsequent reconnection still work.

Reboot recovery and multiple physical headsets need separate verification. Document
what you actually tested; the original Pixel verification did not include a reboot.
A small compatibility report with firmware build, redacted signal, code changes,
and test results is more useful than claiming support for an entire brand.

## 6. Share the implementation

Update [verification](verification.md) with the tested configuration and limits,
then open a pull request with the parser/API changes and regression fixtures.
Avoid changing the default trusted package set or weakening checks for devices
that were not tested. Preserve the original restoration journal across updates.

For your own signed APK, follow the signing instructions in the [README](../README.md#build).
Use your own private key; official release signing files are not in this repository.
