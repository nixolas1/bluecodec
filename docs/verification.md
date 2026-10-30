# Verification

## Connected-device carousel and sound output, 2026-10-30

Debug build and lint passed; 56 instrumentation tests passed on the Pixel 10 Pro.
New controller tests cover confirmed output readback, duplicate-tap suppression,
and preserving actual route state with a visible failure. Selection tests now
require configuration selection to remain independent of playback routing.

Installed in place without clearing application data. With Onyx Studio 6 and
Nico's Soundbar both connected, the native carousel swiped between their cards;
speaker avatars, compact codec controls, and page indicators were inspected on
the device. Both output radio selections were confirmed by the UI and Android
audio-policy readback. The final phone-speaker selection was also verified by
Android's current-output command. These checks verify routing, not audible
output or volume levels.

After exercising these routes, the user reported that Android's system output
picker also worked again. A fresh system dump included Bluetooth A2DP in its
connected-output list and correctly selected Onyx in MediaRouter2. Route switching
may have refreshed the previously inconsistent device state; the exact trigger
and durability of that recovery are not established.

The no-connections fallback and multi-codec help states are implemented but were
not exercised with physical device disconnection or a multi-codec headset in
this pass. Routing currently covers the classic A2DP devices exposed by BlueCodec;
LE Audio, hearing aids, and casting are not supported by this control.

Initial release: v0.1.0.

## Physical device

Tested on a Pixel 10 Pro running Android 17 with Sony WH-1000XM3 headphones and
Shizuku 13.6.0. Codec selection and automatic reapplication were verified with
Android Bluetooth diagnostics; aptX HD was confirmed at 48 kHz / 24 bit.

The Quick Settings tile was tested in compact and wide layouts. Tap switched
Bluetooth off and on; long press opened Android Connected devices. The wide tile
read the saved headset name and 90% battery from Android.

Two shutdown/startup cycles confirmed no automatic headset reconnection, followed
by successful manual connection from Android Settings. One cycle included an app
process restart while Bluetooth was off. Only the chosen device's two audio
profiles were opened; the other 31 managed profiles stayed blocked. Restoration
of original connection policies was verified separately.

## Automated checks

41 instrumentation tests passed using fake Bluetooth services and isolated
preferences: retry/cancellation behavior, policy journaling/restoration, shutdown
ordering, manual connection ownership, log parsing/freshness, and tile states.
Debug/release builds and lint are checked before publishing. Release APK signatures
are verified with Android SDK apksigner.

## Limits

No phone reboot test was performed. Two-device tile presentation has automated
coverage, but was not tested with two physical connected headsets. OEMs, other
Android versions, LE Audio, and root-mode Shizuku are not covered by this device
test. Android Settings connection handling depends on firmware log behavior.

The release UI uses a reconnect toggle and Add Bluetooth tile action. Tests verify
that enabling the mode alone does not alter policies, normal tile mode only changes
power, and blocking mode requires verified policies before shutdown.

## v0.1.3: device selection and volume synchronization

54 instrumentation tests pass, including 13 new selection/recovery tests. Selection
follows the active A2DP route on opening and route changes, newly connected devices,
and falls back when the selected device disconnects. A manually chosen offline device
remains selected during ordinary refreshes so its preferences can still be edited.

On the Pixel 10 Pro / Android 17 with the Samsung soundbar, the original fault was:
A2DP had an active soundbar, AVRCP advertised absolute-volume support, but its volume
manager had no current device and AudioService reported absolute volume unsupported.
The phone reached 25/25 without sending volume commands to the soundbar. Restoring
`AudioManager.setDeviceVolumeBehavior` for that A2DP device re-enabled phone volume
commands; one up/down pair produced AVRCP values 46 then 41 (phone indices 9 then 8).
Rebuilding the A2DP route alone did not fix the registration.

The Shizuku service checks for this specific mismatch after connection settling.
Recovery requires a single native AVRCP connection, enabled absolute volume, an
explicit positive capability, matching active device, a missing volume-manager route,
and variable volume behavior. Unknown diagnostic formats and ambiguous devices are
left alone. It lowers the phone to the lesser of its current setting and the reported
accessory volume before restoring synchronization, avoiding a jump from a previously
maxed-out phone slider. It does not disable hearing protections or force maximum volume.
Diagnostics are read in memory with size/time limits; no device dump is saved by the app.

This is a compatibility workaround for the observed Android routing defect, not a
Bluetooth firmware patch. The device's audible maximum and unrelated firmware volume
bugs are not covered by these tests. The platform APIs and diagnostic format are
firmware-dependent; failures appear in Connection details.

Platform reference: [AOSP AVRCP volume registration](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/avrcp/AvrcpVolumeManager.java).

The matching debug-signed v0.1.3 was installed in place on the user's Pixel; the
public release uses its existing separate release certificate. The app's recovery
worker ran without API errors. The soundbar disconnected during its final check;
the subsequent Onyx test below verified a complete unattended recovery cycle.
Switching between two simultaneously connected physical audio devices remains
covered by regression tests only.

### Physical follow-up: Onyx Studio 6, 2026-10-28

The user authorized testing with HK Onyx Studio 6. Android Settings connected it,
and BlueCodec automatically selected it. Manually selecting a disconnected device,
backgrounding BlueCodec, and reopening it returned selection to the connected Onyx.

A disconnect/reconnect reproduced the volume fault naturally: A2DP had the active
Onyx while AVRCP's volume manager had `mCurrentDevice: null`. At 15:00:30 local time,
the installed app automatically lowered the phone from index 6 to 5 and restored
absolute-volume registration. AudioService confirmed support=true, and BlueCodec's
Connection status displayed “Bluetooth volume synchronization restored.” Subsequent
phone volume commands reached index 7 (AVRCP 36), exceeding the pre-disconnect index
6 (AVRCP 30), then were lowered again. The Onyx remained connected at a low level.
This completes one physical unattended recovery cycle; it does not verify maximum
acoustic output or simultaneous switching between two connected physical devices.
