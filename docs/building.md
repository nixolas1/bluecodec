# Building BlueCodec


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

