# Manual reconnect design

**Experimental.** Physically tested on Pixel 10 Pro / Android 17 with Sony WH-1000XM3. See the [device adaptation guide](device-compatibility.md) for other firmware.

The “Disable automatic reconnect” toggle makes the BlueCodec Quick Settings tile block classic audio profiles (A2DP and HFP) before switching Bluetooth off. Enabling the toggle alone does not change connection policies; disabling it restores saved policies. LE Audio, keyboards, watches, and other non-audio profiles are outside its scope.

## Mechanism

`AudioPolicyAccess` runs inside the Shizuku shell user service. It resolves the installed framework's typed `IBluetoothA2dp` / `IBluetoothHeadset` interfaces through their actual profile proxies; it never uses Binder transaction numbers. The normal Java proxy accepts only ALLOWED/FORBIDDEN policies, so the typed service interface is needed for UNKNOWN.

`ReconnectSettings` journals every original ALLOWED audio policy in app-private SharedPreferences with a synchronous commit before mutation. Existing FORBIDDEN policies are left untouched. For a currently connected/connecting audio device, it first writes UNKNOWN without disconnecting playback. For a disconnected device it writes FORBIDDEN during the first 2 seconds after adapter startup, blocking both outgoing and incoming profile connections. The monitor then changes owned profiles to UNKNOWN to permit headset-initiated connections. Disconnections after this window do not re-block the headset. A recreated app process conservatively starts a fresh 2-second window; unavailable Shizuku delays release until access recovers. This timing has not yet been physically verified.

UNKNOWN alone and shutdown-broadcast blocking both failed physical validation on the target Pixel/Sony headset. The broadcast arrives too late to reliably change policies. The application therefore uses the BlueCodec tile’s explicit shutdown sequence instead.

`turnBluetoothOff` reads and journals eligible policies while Bluetooth is ON, writes FORBIDDEN to every owned profile (including active devices), then reads the complete policy snapshot again. It calls the shell service's adapter disable only after all owned profiles are confirmed FORBIDDEN and no new ALLOWED profile appeared. A failed write/readback restores originals without requesting shutdown. Once shutdown is requested, an error retains the durable journal because radio state may be uncertain. The user service invokes the fixed platform shell command `/system/bin/cmd bluetooth_manager disable` as UID 2000 (three-second process timeout with forced cleanup; output discarded to avoid pipe blockage). This avoids the bootstrap adapter's invalid package attribution. It then waits at most five seconds for STATE_OFF and reports success only after observing it. Blocking disconnects current audio before radio shutdown.

System Bluetooth controls do not arm this protection. After an explicit connection, use the BlueCodec tile again for the next shutdown. Active UNKNOWN policies never report ready.
An explicit BlueCodec Connect opens only that device's journaled profiles to UNKNOWN and calls the typed profile `connect` methods. A 15-second window prevents connection-state races from closing the policy before the request can complete; the controller reconciles again afterward. After the startup window, owned profiles remain UNKNOWN until the next Bluetooth shutdown, including when disconnected. Android’s normal connection path cannot connect FORBIDDEN profiles. The manual-request bridge described below handles supported Settings/SystemUI requests through the same per-device Connect path.

This is a device policy change, not interception of Android's Bluetooth tile. LE Audio remains outside scope.
Disabling restores only policies that still equal the app's applied value or the previous value of an interrupted write, preserving later external changes. A service death or interrupted restore leaves the durable journal for retry when Shizuku and Bluetooth return. Restoring ALLOWED asks Android to connect that profile, so disabling can reconnect nearby devices. Uninstalling or clearing app storage before restoration loses the restore journal; turn the setting off and wait for confirmation first.

## Integration

- Construct `ReconnectSettings` in the app controller.
- Run `setEnabled`, `reconcile`, and `connect` on its serial worker and publish afterward.
- Reconcile after the shell user service is ready, Bluetooth reaches ON, a profile connects/disconnects while Bluetooth remains ON, or a pairing changes.
- Call `turnBluetoothOff(service)` on the serial worker when the tile switches Bluetooth off with the setting enabled. `operationFailed()` reports its last result. Do not reconcile during this operation or while Bluetooth is OFF. No shutdown broadcast mutation is used.
- After explicit Connect, reconcile again after 15 seconds.
- Keep monitoring available while `needsService()` is true, independently of codec preferences.
- `enabled()` is intent; `ready()` is verified state in this process. Display `status()` for unavailable access or a pending restore.
- A new process starts with `ready() == false` even if a saved enabled setting exists.
- `connect()` only reports a request. The normal device snapshot establishes actual connection.

## Primary source contracts inspected

These are moving AOSP references, not proof of Pixel firmware behavior:

- [PhonePolicy](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/btservice/PhonePolicy.java): automatic classic-audio connection requires ALLOWED.
- [A2dpService](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/a2dp/A2dpService.java) and [HeadsetService](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/hfp/HeadsetService.java): setting UNKNOWN saves policy without disconnecting; explicit connect rejects FORBIDDEN, not UNKNOWN.
- [DatabaseManager](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/btservice/storage/DatabaseManager.java): accepts UNKNOWN, FORBIDDEN, and ALLOWED.
- [BluetoothServiceBinder](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/service/src/com/android/server/bluetooth/BluetoothServiceBinder.java): `enableNoAutoConnect` is NFC-UID restricted, so it is not a generic Shizuku solution.

## Verification

`ReconnectSettingsTest` exercises the policy journal using a fake Binder boundary and isolated preferences: pre-write persistence, process recreation, deferred restore after service loss, external-change preservation, partial-failure rollback, re-arming, disconnected-profile blocking, explicit connection, block-before-disable ordering, rejected writes and readback drift preventing shutdown, and restoration after uncertain shutdown.

Target-device testing must independently confirm the device remains disconnected after Bluetooth is turned off and on, explicit Connect works, and disabling restores the prior policies. Do not substitute a successful setter return for these checks.


## Android Settings manual connection bridge

`ManualConnectionRequests` in the Shizuku process reads only the live
`BluetoothAdapterServiceBinder` log tag. An exact `connectAllEnabledProfiles`
request is accepted only when the log producer's UID and /proc identity match the
installed system Bluetooth package and the Binder caller's UID and /proc identity
match Settings or SystemUI. This request precedes Android's enabled-profile filter;
FORBIDDEN profiles otherwise cause it to do nothing.

Full addresses or anonymized suffixes must resolve to exactly one bonded device.
Entries older than reader startup or five seconds are rejected. The in-memory
queue is capped at 32 and deduplicated on drain; log lines are never persisted.
The controller additionally requires reconnect blocking to remain enabled and
the selected device to be in the durable restore journal. It then opens only that
device through the existing explicit Connect path. No blanket opening window is
used. The foreground monitor polls the queue every 750 ms; stopping monitoring or
restoring normal connection policies closes the log reader.

This is firmware-dependent compatibility behavior verified on the Pixel, not a
portable Android API. Unsupported/failed reader startup reports an actionable error
in BlueCodec. Missing or changed log signals fail closed. The app's Connect remains
available, and Restore automatic reconnect restores Android's normal behavior.
