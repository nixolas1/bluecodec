package no.nixo.bluecodec;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioManager;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Repairs a missing AudioService registration, never changes Bluetooth's capability policy. */
final class VolumeSyncRepair implements Runnable {
  private final Supplier<BluetoothA2dp> profile;
  private final AudioManager audio;
  private String lastAddress = "";
  private long connectedAt, checkedAt;
  private boolean repaired;
  volatile String status = "Waiting for an active audio device";

  VolumeSyncRepair(Context shell, Supplier<BluetoothA2dp> profile) throws Exception {
    this.profile = profile;
    // A shell system context has no Application; AudioManager requires a non-null one.
    Context attributed =
        new ContextWrapper(shell) {
          public Context getApplicationContext() {
            return this;
          }
        };
    audio = AudioManager.class.getConstructor(Context.class).newInstance(attributed);
  }

  /**
   * Reject unfamiliar/ambiguous dumps. Redacted addresses are matched only within AVRCP sections.
   */
  static int remoteVolume(String dump, String address) {
    if (!address.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) return -1;
    String[] sections = dump.split("AvrcpVolumeManager:", -1);
    if (sections.length != 2) return -1;
    String manager = sections[1].split("BTAudio Volume Events:", 2)[0];
    if (!manager.contains("mCurrentDevice: null")) return -1;
    Matcher support =
        Pattern.compile("(?im)^\\s*([0-9a-fx:]{17}): (true|false)\\s*$").matcher(manager);
    int matches = 0;
    boolean supported = false;
    while (support.find()) {
      String device = support.group(1).toUpperCase(Locale.ROOT);
      if (matchesAddress(device, address)) {
        matches++;
        supported = support.group(2).equals("true");
      }
    }
    if (matches != 1 || !supported) return -1;
    String[] nativeSections = dump.split("AVRCP Target Native Service:", -1);
    if (nativeSections.length != 2) return -1;
    String nativeState = nativeSections[1];
    if (!nativeState.matches("(?s)^ 1 devices\\s+Absolute Volume Enabled: true\\s+.*")) return -1;
    Matcher active =
        Pattern.compile("(?im)^\\s*([0-9a-fx:]{17}) <Active>\\s+Current Volume: (\\d+)\\s*$")
            .matcher(nativeState);
    if (!active.find() || !matchesAddress(active.group(1), address)) return -1;
    int volume = Integer.parseInt(active.group(2));
    return volume <= 127 && !active.find() ? volume : -1;
  }

  private static boolean matchesAddress(String reported, String address) {
    return reported.equalsIgnoreCase(address)
        || reported.equalsIgnoreCase("XX:XX:XX:XX:" + address.substring(12));
  }

  static int safeVolume(int phone, int remote, int max) {
    if (phone < 0 || max <= 0 || phone > max || remote < 0 || remote > 127) return -1;
    return Math.min(phone, remote * max / 127);
  }

  private static String bluetoothDump() throws Exception {
    java.lang.Process process =
        new ProcessBuilder("/system/bin/dumpsys", "bluetooth_manager", "--print")
            .redirectErrorStream(true)
            .start();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Thread reader =
        new Thread(
            () -> {
              try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int size;
                while ((size = stream.read(buffer)) >= 0) {
                  if (bytes.size() + size > 2 * 1024 * 1024) {
                    process.destroy();
                    return;
                  }
                  bytes.write(buffer, 0, size);
                }
              } catch (Exception ignored) {
              }
            },
            "bluecodec-volume-dump");
    reader.setDaemon(true);
    reader.start();
    try {
      if (!process.waitFor(4, TimeUnit.SECONDS))
        throw new IllegalStateException("Bluetooth diagnostics timed out");
      reader.join(1000);
      if (reader.isAlive() || process.exitValue() != 0)
        throw new IllegalStateException("Bluetooth diagnostics unavailable");
      return bytes.toString(StandardCharsets.UTF_8.name());
    } finally {
      process.destroy();
    }
  }

  @Override
  public void run() {
    try {
      BluetoothA2dp a2dp = profile.get();
      BluetoothDevice active =
          a2dp == null ? null : (BluetoothDevice) CodecService.call(a2dp, "getActiveDevice");
      String address = active == null ? "" : active.getAddress();
      long now = SystemClock.elapsedRealtime();
      if (!address.equals(lastAddress)) {
        lastAddress = address;
        connectedAt = now;
        checkedAt = 0;
        repaired = false;
      }
      if (active == null) {
        status = "Waiting for an active audio device";
        return;
      }
      if (now - connectedAt < 5000) return;
      Class<?> attributes = Class.forName("android.media.AudioDeviceAttributes");
      Object output =
          attributes.getConstructor(int.class, int.class, String.class).newInstance(2, 8, address);
      int variable = AudioManager.class.getField("DEVICE_VOLUME_BEHAVIOR_VARIABLE").getInt(null);
      int absolute = AudioManager.class.getField("DEVICE_VOLUME_BEHAVIOR_ABSOLUTE").getInt(null);
      int behavior =
          (Integer)
              audio
                  .getClass()
                  .getMethod("getDeviceVolumeBehavior", attributes)
                  .invoke(audio, output);
      if (behavior != variable) {
        status =
            repaired
                ? "Bluetooth volume synchronization restored"
                : "Android manages this device’s volume";
        return;
      }
      if (checkedAt != 0 && now - checkedAt < 30000) return;
      checkedAt = now;
      String dump = bluetoothDump();
      int remote = remoteVolume(dump, address);
      if (remote < 0) {
        status = "No confirmed volume synchronization fault";
        return;
      }
      // Do not act on a route that changed while collecting diagnostics.
      if (profile.get() != a2dp || !active.equals(CodecService.call(a2dp, "getActiveDevice")))
        return;
      int safe =
          safeVolume(
              audio.getStreamVolume(AudioManager.STREAM_MUSIC),
              remote,
              audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
      if (safe < 0) return;
      // Lower before registering: the phone may be at max while the accessory is quiet.
      audio.setStreamVolume(AudioManager.STREAM_MUSIC, safe, 0);
      BluetoothA2dp.class.getMethod("setAvrcpAbsoluteVolume", int.class).invoke(a2dp, safe);
      audio
          .getClass()
          .getMethod("setDeviceVolumeBehavior", attributes, int.class)
          .invoke(audio, output, absolute);
      int verified =
          (Integer)
              audio
                  .getClass()
                  .getMethod("getDeviceVolumeBehavior", attributes)
                  .invoke(audio, output);
      repaired = verified == absolute;
      status =
          repaired
              ? "Bluetooth volume synchronization restored"
              : "Volume synchronization repair not confirmed";
    } catch (Throwable error) {
      status = "Volume synchronization check unavailable: " + CodecService.error(error);
    }
  }
}
