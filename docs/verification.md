# Verification

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
