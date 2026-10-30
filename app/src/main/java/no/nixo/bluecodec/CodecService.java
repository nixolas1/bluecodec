package no.nixo.bluecodec;

import android.bluetooth.*;
import android.content.Context;
import android.os.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** Runs only in a Shizuku user service. No hardcoded platform Binder transactions. */
@android.annotation.SuppressLint(
    "MissingPermission") // Shell process: permission/API failures are returned to the caller, never
// hidden.
public class CodecService extends ICodecService.Stub {
  private volatile BluetoothA2dp a2dp;
  private volatile String initialization = "Waiting for A2DP service";
  private BluetoothAdapter adapter;
  private volatile AudioPolicyAccess audioPolicies;
  private volatile SoundOutput soundOutput;
  private volatile ManualConnectionRequests manualRequests;
  private volatile VolumeSyncRepair volumeSync;
  private final java.util.concurrent.ScheduledExecutorService volumeWorker =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor();

  public CodecService() {
    new Handler(Looper.getMainLooper())
        .post(
            () -> {
              try {
                Class<?> initializer =
                    Class.forName("android.bluetooth.BluetoothFrameworkInitializer");
                if (initializer.getMethod("getBluetoothServiceManager").invoke(null) == null) {
                  Class<?> manager = Class.forName("android.os.BluetoothServiceManager");
                  initializer
                      .getMethod("setBluetoothServiceManager", manager)
                      .invoke(null, manager.getConstructor().newInstance());
                }
                Class<?> at = Class.forName("android.app.ActivityThread");
                Object thread = at.getMethod("currentActivityThread").invoke(null);
                if (thread == null) thread = at.getMethod("systemMain").invoke(null);
                Context system = (Context) at.getMethod("getSystemContext").invoke(thread);
                Context shell = system.createPackageContext("com.android.shell", 0);
                adapter = shell.getSystemService(BluetoothManager.class).getAdapter();
                try {
                  soundOutput = new SoundOutput(shell, () -> a2dp);
                } catch (Exception ignored) {
                  /* Unsupported routing must not disable codecs. */
                }
                try {
                  volumeSync = new VolumeSyncRepair(shell, () -> a2dp);
                  volumeWorker.scheduleWithFixedDelay(
                      volumeSync, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception ignored) {
                  // Unsupported volume APIs must not prevent codec controls from starting.
                }
                audioPolicies = new AudioPolicyAccess(adapter);
                manualRequests = new ManualConnectionRequests(shell, adapter);
                adapter.getProfileProxy(
                    shell,
                    new BluetoothProfile.ServiceListener() {
                      public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        a2dp = (BluetoothA2dp) proxy;
                        audioPolicies.connected(profile, proxy);
                      }

                      public void onServiceDisconnected(int profile) {
                        a2dp = null;
                        audioPolicies.disconnected(profile);
                        initialization = "A2DP service disconnected";
                      }
                    },
                    BluetoothProfile.A2DP);
                adapter.getProfileProxy(
                    shell,
                    new BluetoothProfile.ServiceListener() {
                      public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        audioPolicies.connected(profile, proxy);
                      }

                      public void onServiceDisconnected(int profile) {
                        audioPolicies.disconnected(profile);
                      }
                    },
                    BluetoothProfile.HEADSET);
              } catch (Throwable e) {
                initialization = error(e);
              }
            });
  }

  static String error(Throwable e) {
    while (e instanceof InvocationTargetException && e.getCause() != null) e = e.getCause();
    return e.getClass().getName() + ": " + e.getMessage();
  }

  static Object call(Object target, String name) throws Exception {
    return target.getClass().getMethod(name).invoke(target);
  }

  Object status(BluetoothDevice device) throws Exception {
    return BluetoothA2dp.class
        .getMethod("getCodecStatus", BluetoothDevice.class)
        .invoke(a2dp, device);
  }

  JSONObject codec(Object c) throws Exception {
    return new JSONObject()
        .put("type", call(c, "getCodecType"))
        .put(
            "name",
            c.getClass()
                .getMethod("getCodecName", int.class)
                .invoke(null, call(c, "getCodecType")));
  }

  private static String displayName(BluetoothDevice device) {
    String alias = device.getAlias();
    if (alias != null && !alias.isBlank()) return alias;
    String name = device.getName();
    return name == null || name.isBlank() ? "Headphones" : name;
  }

  public String inspect() {
    JSONObject result = new JSONObject();
    try {
      result.put("uid", android.os.Process.myUid());
      if (soundOutput != null) result.put("soundOutput", soundOutput.inspect());
      if (volumeSync != null) result.put("volumeSync", volumeSync.status);
      if (adapter != null && !adapter.isEnabled())
        return result.put("bluetoothEnabled", false).put("devices", new JSONArray()).toString();
      JSONArray tileDevices = new JSONArray();
      for (BluetoothDevice device : adapter.getBondedDevices()) {
        if (!Boolean.TRUE.equals(call(device, "isConnected"))) continue;
        int battery = -1;
        try {
          battery = ((Number) call(device, "getBatteryLevel")).intValue();
        } catch (Exception ignored) {
        }
        tileDevices.put(new JSONObject().put("name", displayName(device)).put("battery", battery));
      }
      result.put("tileDevices", tileDevices).put("bluetoothEnabled", adapter.isEnabled());
      if (a2dp == null) return result.put("error", initialization).toString();
      JSONArray devices = new JSONArray();
      List<BluetoothDevice> active = a2dp.getConnectedDevices();
      BluetoothDevice routed = null;
      try {
        routed = (BluetoothDevice) call(a2dp, "getActiveDevice");
      } catch (Exception ignored) {
        // Some firmware omits this hidden API; connected-device selection still works.
      }
      List<BluetoothDevice> paired = new ArrayList<>(adapter.getBondedDevices());
      paired.sort(
          Comparator.comparing((BluetoothDevice d) -> !active.contains(d))
              .thenComparing(d -> displayName(d)));
      result.put("bluetoothEnabled", adapter.isEnabled());
      for (BluetoothDevice device : paired) {
        BluetoothClass category = device.getBluetoothClass();
        if (!active.contains(device)
            && category != null
            && category.getMajorDeviceClass() != BluetoothClass.Device.Major.AUDIO_VIDEO) continue;
        JSONObject d =
            new JSONObject()
                .put("address", device.getAddress())
                .put("name", displayName(device))
                .put("speaker", soundOutput != null && soundOutput.speaker(device))
                .put("active", device.equals(routed))
                .put("connected", active.contains(device));
        if (!active.contains(device)) {
          devices.put(d);
          continue;
        }
        try {
          Object s = status(device);
          if (s == null) throw new IllegalStateException("Codec status unavailable");
          d.put("current", codec(call(s, "getCodecConfig")));
          Object capabilities = call(s, "getCodecsSelectableCapabilities");
          Iterable<?> list =
              capabilities instanceof Collection
                  ? (Collection<?>) capabilities
                  : Arrays.asList((Object[]) capabilities);
          JSONArray codecs = new JSONArray();
          for (Object c : list) codecs.put(codec(c));
          d.put("selectable", codecs);
        } catch (Throwable e) {
          d.put("error", error(e));
        }
        devices.put(d);
      }
      result.put("devices", devices);
    } catch (Throwable e) {
      try {
        result.put("error", error(e));
      } catch (JSONException ignored) {
      }
    }
    return result.toString();
  }

  public String apply(String address, int type) {
    try {
      if (a2dp == null) throw new IllegalStateException(initialization);
      BluetoothDevice device = null;
      for (BluetoothDevice d : a2dp.getConnectedDevices())
        if (d.getAddress().equals(address)) device = d;
      if (device == null) throw new IllegalStateException("Headset disconnected");
      Object s = status(device);
      Object caps = call(s, "getCodecsSelectableCapabilities");
      Iterable<?> list =
          caps instanceof Collection ? (Collection<?>) caps : Arrays.asList((Object[]) caps);
      boolean supported = false;
      for (Object c : list)
        if (((Number) call(c, "getCodecType")).intValue() == type) supported = true;
      if (!supported)
        throw new IllegalArgumentException(
            "Codec is not currently selectable; system playback retained");
      Class<?> config = Class.forName("android.bluetooth.BluetoothCodecConfig");
      int priority = config.getField("CODEC_PRIORITY_HIGHEST").getInt(null);
      Object preference =
          config
              .getConstructor(
                  int.class,
                  int.class,
                  int.class,
                  int.class,
                  int.class,
                  long.class,
                  long.class,
                  long.class,
                  long.class)
              .newInstance(type, priority, 0, 0, 0, 0L, 0L, 0L, 0L);
      BluetoothA2dp.class
          .getMethod("setCodecConfigPreference", BluetoothDevice.class, config)
          .invoke(a2dp, device, preference);
      return "Request sent. Refresh to verify the negotiated codec.";
    } catch (Throwable e) {
      return error(e);
    }
  }

  public String audioPolicies() {
    AudioPolicyAccess access = audioPolicies;
    return access == null ? "{\"error\":\"Bluetooth is not ready\"}" : access.inspect();
  }

  public String selectSoundOutput(String target) {
    SoundOutput output = soundOutput;
    return output == null ? "{\"error\":\"Sound output is not ready.\"}" : output.select(target);
  }

  public String setAudioPolicy(String address, int profile, int policy) {
    AudioPolicyAccess access = audioPolicies;
    return access == null
        ? "{\"error\":\"Bluetooth is not ready\"}"
        : access.set(address, profile, policy);
  }

  public String connectAudio(String address) {
    AudioPolicyAccess access = audioPolicies;
    return access == null ? "{\"error\":\"Bluetooth is not ready\"}" : access.connect(address);
  }

  @SuppressWarnings("deprecation")
  public String turnBluetoothOff() {
    return setBluetoothPower(false);
  }

  public String turnBluetoothOn() {
    return setBluetoothPower(true);
  }

  private String setBluetoothPower(boolean enabled) {
    JSONObject result = new JSONObject();
    int expected = enabled ? BluetoothAdapter.STATE_ON : BluetoothAdapter.STATE_OFF;
    String resultKey = enabled ? "on" : "off";
    try {
      if (adapter == null) throw new IllegalStateException("Bluetooth is not ready");
      // The framework adapter carries the bootstrap context's "android" attribution,
      // which does not belong to shell UID 2000. The platform shell entry point owns
      // its attribution and exposes exactly this fixed operation; no caller commands.
      java.lang.Process shutdown =
          new ProcessBuilder("/system/bin/cmd", "bluetooth_manager", enabled ? "enable" : "disable")
              .redirectErrorStream(true)
              .redirectOutput(new java.io.File("/dev/null"))
              .start();
      boolean accepted;
      try {
        if (!shutdown.waitFor(3, java.util.concurrent.TimeUnit.SECONDS))
          throw new IllegalStateException("Bluetooth shutdown command timed out");
        int exitCode = shutdown.exitValue();
        result.put("commandExitCode", exitCode);
        accepted = exitCode == 0;
      } finally {
        if (shutdown.isAlive()) {
          shutdown.destroyForcibly();
          shutdown.waitFor(1, java.util.concurrent.TimeUnit.SECONDS);
        }
      }
      result.put("accepted", accepted);
      long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
      while (accepted
          && adapter.getState() != expected
          && android.os.SystemClock.elapsedRealtime() < deadline) {
        android.os.SystemClock.sleep(100);
      }
      int state = adapter.getState();
      result.put("state", state).put(resultKey, state == expected);
      if (state != expected)
        result.put(
            "error",
            accepted
                ? "Bluetooth did not confirm the requested state within 5 seconds"
                : "Android rejected the Bluetooth change");
    } catch (Throwable e) {
      try {
        result.put(resultKey, false).put("error", error(e));
      } catch (JSONException ignored) {
      }
    }
    return result.toString();
  }

  public String manualConnectionRequests(boolean enabled) {
    ManualConnectionRequests requests = manualRequests;
    return requests == null ? "{\"available\":false,\"requests\":[]}" : requests.drain(enabled);
  }

  public void destroy() {
    volumeWorker.shutdownNow();
    if (manualRequests != null) manualRequests.close();
    System.exit(0);
  }
}
