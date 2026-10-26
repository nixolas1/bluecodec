package no.nixo.bluecodec;

import android.bluetooth.*;
import android.content.AttributionSource;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.json.*;

/** Shell-only bridge for classic audio connection policy. No platform transaction IDs. */
@android.annotation.SuppressLint("MissingPermission")
final class AudioPolicyAccess {
  private final BluetoothAdapter adapter;
  private final Map<Integer, BluetoothProfile> profiles = new ConcurrentHashMap<>();

  AudioPolicyAccess(BluetoothAdapter adapter) {
    this.adapter = adapter;
  }

  void connected(int id, BluetoothProfile profile) {
    profiles.put(id, profile);
  }

  void disconnected(int id) {
    profiles.remove(id);
  }

  private static int constant(String name) throws Exception {
    return BluetoothProfile.class.getField(name).getInt(null);
  }

  private static void checkProfile(int id) {
    if (id != BluetoothProfile.A2DP && id != BluetoothProfile.HEADSET)
      throw new IllegalArgumentException("Only classic audio profiles are supported");
  }

  private Object invoke(int id, String method, BluetoothDevice device, Integer policy)
      throws Exception {
    checkProfile(id);
    BluetoothProfile proxy = profiles.get(id);
    if (proxy == null) throw new IllegalStateException("Audio profile " + id + " is not ready");
    Method getter = proxy.getClass().getDeclaredMethod("getService");
    getter.setAccessible(true);
    Object service = getter.invoke(proxy);
    if (service == null)
      throw new IllegalStateException("Audio profile " + id + " is disconnected");
    Field attribution = proxy.getClass().getDeclaredField("mAttributionSource");
    attribution.setAccessible(true);
    Object source = attribution.get(proxy);
    Class<?> contract =
        Class.forName(
            id == BluetoothProfile.A2DP
                ? "android.bluetooth.IBluetoothA2dp"
                : "android.bluetooth.IBluetoothHeadset");
    if (policy != null)
      return contract
          .getMethod(method, BluetoothDevice.class, int.class, AttributionSource.class)
          .invoke(service, device, policy, source);
    return contract
        .getMethod(method, BluetoothDevice.class, AttributionSource.class)
        .invoke(service, device, source);
  }

  String inspect() {
    JSONObject result = new JSONObject();
    try {
      if (!adapter.isEnabled())
        throw new IllegalStateException("Turn Bluetooth on to manage reconnect settings");
      // Require both classic audio services so a half-ready stack cannot claim full protection.
      if (!profiles.containsKey(BluetoothProfile.A2DP)
          || !profiles.containsKey(BluetoothProfile.HEADSET))
        throw new IllegalStateException("Waiting for audio profiles");
      result
          .put("allowed", constant("CONNECTION_POLICY_ALLOWED"))
          .put("unknown", constant("CONNECTION_POLICY_UNKNOWN"))
          .put("forbidden", constant("CONNECTION_POLICY_FORBIDDEN"));
      JSONArray rows = new JSONArray();
      for (BluetoothDevice device : adapter.getBondedDevices()) {
        for (int id : new int[] {BluetoothProfile.A2DP, BluetoothProfile.HEADSET}) {
          int value = (Integer) invoke(id, "getConnectionPolicy", device, null);
          int state = (Integer) invoke(id, "getConnectionState", device, null);
          rows.put(
              new JSONObject()
                  .put("address", device.getAddress())
                  .put("profile", id)
                  .put("policy", value)
                  .put("state", state)
                  .put("connected", state == BluetoothProfile.STATE_CONNECTED)
                  .put("connecting", state == BluetoothProfile.STATE_CONNECTING));
        }
      }
      result.put("policies", rows);
    } catch (Throwable e) {
      putError(result, e);
    }
    return result.toString();
  }

  String set(String address, int profile, int policy) {
    JSONObject result = new JSONObject();
    try {
      checkProfile(profile);
      if (policy != constant("CONNECTION_POLICY_ALLOWED")
          && policy != constant("CONNECTION_POLICY_UNKNOWN")
          && policy != constant("CONNECTION_POLICY_FORBIDDEN"))
        throw new IllegalArgumentException("Unsupported connection policy");
      BluetoothDevice device = adapter.getRemoteDevice(address);
      if (device.getBondState() != BluetoothDevice.BOND_BONDED)
        throw new IllegalStateException("Device is no longer paired");
      boolean accepted = (Boolean) invoke(profile, "setConnectionPolicy", device, policy);
      int actual = (Integer) invoke(profile, "getConnectionPolicy", device, null);
      result
          .put("accepted", accepted)
          .put("policy", actual)
          .put("verified", accepted && actual == policy);
    } catch (Throwable e) {
      putError(result, e);
    }
    return result.toString();
  }

  String connect(String address) {
    JSONObject result = new JSONObject();
    try {
      if (!adapter.isEnabled()) throw new IllegalStateException("Turn Bluetooth on first");
      BluetoothDevice device = adapter.getRemoteDevice(address);
      if (device.getBondState() != BluetoothDevice.BOND_BONDED)
        throw new IllegalStateException("Device is no longer paired");
      JSONArray attempts = new JSONArray();
      boolean any = false;
      for (int id : new int[] {BluetoothProfile.A2DP, BluetoothProfile.HEADSET}) {
        JSONObject row = new JSONObject().put("profile", id);
        try {
          int policy = (Integer) invoke(id, "getConnectionPolicy", device, null);
          if (policy == constant("CONNECTION_POLICY_FORBIDDEN")) {
            row.put("skipped", "Disabled in Android settings");
          } else {
            boolean accepted = (Boolean) invoke(id, "connect", device, null);
            row.put("accepted", accepted);
            any |= accepted;
          }
        } catch (Throwable e) {
          putError(row, e);
        }
        attempts.put(row);
      }
      result.put("requested", any).put("profiles", attempts);
    } catch (Throwable e) {
      putError(result, e);
    }
    return result.toString();
  }

  private static void putError(JSONObject data, Throwable failure) {
    try {
      data.put("error", CodecService.error(failure));
    } catch (JSONException ignored) {
    }
  }
}
