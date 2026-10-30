package no.nixo.bluecodec;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.SystemClock;
import java.util.List;
import java.util.function.Supplier;
import org.json.JSONObject;

/** Shell-only media routing. Read policy directly, not MediaRouter's cached device list. */
final class SoundOutput {
  private final AudioManager audio;
  private final Supplier<BluetoothA2dp> profile;
  private final AudioAttributes media =
      new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();

  SoundOutput(Context shell, Supplier<BluetoothA2dp> profile) throws Exception {
    Context context =
        new ContextWrapper(shell) {
          public Context getApplicationContext() {
            return this;
          }
        };
    audio = AudioManager.class.getConstructor(Context.class).newInstance(context);
    this.profile = profile;
  }

  String current() throws Exception {
    List<?> devices =
        (List<?>)
            AudioManager.class
                .getMethod("getDevicesForAttributes", AudioAttributes.class)
                .invoke(audio, media);
    if (devices.size() != 1) return "";
    Object device = devices.get(0);
    int type = (Integer) device.getClass().getMethod("getType").invoke(device);
    if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return "phone";
    if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
      return (String) device.getClass().getMethod("getAddress").invoke(device);
    return "";
  }

  boolean speaker(BluetoothDevice device) {
    try {
      int category =
          (Integer)
              AudioManager.class
                  .getMethod("getBluetoothAudioDeviceCategory", String.class)
                  .invoke(audio, device.getAddress());
      int speaker = AudioManager.class.getField("AUDIO_DEVICE_CATEGORY_SPEAKER").getInt(null);
      if (category != 0) return category == speaker;
    } catch (ReflectiveOperationException ignored) {
      // Older Android builds have no user-assigned audio category API.
    }
    try {
      return device.getBluetoothClass() != null
          && device.getBluetoothClass().getDeviceClass()
              == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER;
    } catch (SecurityException ignored) {
      return false;
    }
  }

  JSONObject inspect() {
    JSONObject state = new JSONObject();
    try {
      state.put("selected", current());
    } catch (Exception e) {
      try {
        state.put("error", "Sound output unavailable. Try reconnecting Shizuku.");
      } catch (Exception ignored) {
      }
    }
    return state;
  }

  @android.annotation.SuppressLint("MissingPermission")
  String select(String target) {
    JSONObject result = new JSONObject();
    try {
      BluetoothDevice device = null;
      if (!"phone".equals(target)) {
        BluetoothA2dp a2dp = profile.get();
        if (a2dp != null)
          for (BluetoothDevice candidate : a2dp.getConnectedDevices())
            if (candidate.getAddress().equals(target)) device = candidate;
        if (device == null) throw new IllegalStateException("Device is no longer connected.");
      }
      Class<?> strategyClass = Class.forName("android.media.audiopolicy.AudioProductStrategy");
      List<?> strategies =
          (List<?>) AudioManager.class.getMethod("getAudioProductStrategies").invoke(null);
      Object strategy = null;
      for (Object candidate : strategies)
        if (Boolean.TRUE.equals(
            strategyClass
                .getMethod("supportsAudioAttributes", AudioAttributes.class)
                .invoke(candidate, media))) {
          strategy = candidate;
          break;
        }
      if (strategy == null) throw new IllegalStateException("Media routing is unavailable.");
      if (device != null
          && !Boolean.TRUE.equals(
              BluetoothA2dp.class
                  .getMethod("setActiveDevice", BluetoothDevice.class)
                  .invoke(profile.get(), device)))
        throw new IllegalStateException("Android could not activate this device.");
      Class<?> attributesClass = Class.forName("android.media.AudioDeviceAttributes");
      Object attributes =
          attributesClass
              .getConstructor(int.class, int.class, String.class)
              .newInstance(
                  2,
                  device == null
                      ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                      : AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                  device == null ? "" : target);
      if (!Boolean.TRUE.equals(
          AudioManager.class
              .getMethod("setPreferredDeviceForStrategy", strategyClass, attributesClass)
              .invoke(audio, strategy, attributes)))
        throw new IllegalStateException("Android rejected the sound output change.");
      long deadline = SystemClock.elapsedRealtime() + 5000;
      do {
        if (target.equals(current())) return result.put("selected", target).toString();
        SystemClock.sleep(150);
      } while (SystemClock.elapsedRealtime() < deadline);
      throw new IllegalStateException("Sound output did not change. Try again.");
    } catch (Exception e) {
      try {
        result.put(
            "error",
            "Could not switch sound output. "
                + (e instanceof IllegalStateException
                    ? e.getMessage()
                    : "Try reconnecting Shizuku."));
      } catch (Exception ignored) {
      }
      return result.toString();
    }
  }
}
