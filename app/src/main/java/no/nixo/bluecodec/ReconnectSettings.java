package no.nixo.bluecodec;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.util.*;
import org.json.*;

/** Durable classic-audio policy journal. All mutating operations run on the controller worker. */
final class ReconnectSettings {
  private final SharedPreferences store;
  private final Map<String, Long> explicitConnections = new HashMap<>();
  private volatile String status;
  private volatile boolean ready;
  private volatile boolean operationFailed;
  private volatile boolean activeConnection;

  boolean limitedByActiveConnection() {
    return activeConnection && !store.getBoolean("shutdown_failed", false);
  }

  ReconnectSettings(Context context) {
    store = context.getSharedPreferences("manual_reconnect", Context.MODE_PRIVATE);
    status =
        enabled()
            ? "Checking connection settings…"
            : hasPendingRestore()
                ? "Restore pending. Connect Shizuku with Bluetooth on."
                : "Android manages reconnections.";
  }

  boolean enabled() {
    return store.getBoolean("enabled", false);
  }

  boolean hasPendingRestore() {
    return !"[]".equals(store.getString("journal", "[]"));
  }

  boolean needsService() {
    return enabled() || hasPendingRestore();
  }

  boolean ready() {
    return ready;
  }

  String status() {
    return status;
  }

  synchronized String setEnabled(boolean enabled, ICodecService service) {
    if (!store.edit().putBoolean("enabled", enabled).commit())
      return status = "Could not save this setting. Nothing changed.";
    if (!enabled) {
      explicitConnections.clear();
      store.edit().remove("shutdown_failed").commit();
    }
    return reconcile(service);
  }

  synchronized String reconcile(ICodecService service) {
    if (service == null) {
      ready = false;
      activeConnection = false;
      return status =
          enabled()
              ? "Shizuku unavailable. Connection settings not verified."
              : hasPendingRestore()
                  ? "Restore pending. Start Shizuku and turn Bluetooth on."
                  : "Android manages reconnections.";
    }
    boolean attemptedWrite = false;
    try {
      JSONObject snapshot = read(service);
      Map<String, JSONObject> actual = index(snapshot.getJSONArray("policies"));
      Map<String, JSONObject> owned = journal();
      if (!enabled()) {
        activeConnection = false;
        ready = false;
        return restore(service, actual, owned);
      }
      int allowed = snapshot.getInt("allowed"),
          unknown = snapshot.getInt("unknown"),
          forbidden = snapshot.getInt("forbidden");
      if (!store
          .edit()
          .putInt("allowed", allowed)
          .putInt("unknown", unknown)
          .putInt("forbidden", forbidden)
          .commit()) throw new IllegalStateException("Could not save policy constants");
      Set<String> active = new HashSet<>();
      for (JSONObject row : actual.values())
        if (row.optBoolean("connected") || row.optBoolean("connecting"))
          active.add(row.getString("address"));
      long now = SystemClock.elapsedRealtime();
      explicitConnections.entrySet().removeIf(e -> e.getValue() < now);
      active.addAll(explicitConnections.keySet());
      for (JSONObject row : actual.values()) {
        String key = key(row);
        int policy = row.getInt("policy");
        JSONObject entry = owned.get(key);
        if (entry != null && policy == forbidden && !owns(entry, policy)) {
          owned.remove(key);
          continue;
        } // Later user-disabled profiles stay disabled.
        if (policy == allowed && entry == null) {
          entry = new JSONObject(row.toString()).put("original", policy).put("applied", policy);
          owned.put(key, entry);
        }
      }
      saveJournal(owned);
      for (JSONObject entry : owned.values()) {
        JSONObject current = actual.get(key(entry));
        if (current == null) continue;
        int target = active.contains(entry.getString("address")) ? unknown : forbidden;
        if (current.getInt("policy") == target) {
          entry.put("applied", target).remove("previous");
          continue;
        }
        attemptedWrite = true;
        transition(service, owned, entry, current.getInt("policy"), target);
      }
      saveJournal(owned);
      boolean allBlocked = true;
      for (JSONObject entry : owned.values()) {
        if (entry.getInt("applied") != forbidden || entry.has("previous")) {
          allBlocked = false;
          break;
        }
      }
      activeConnection = !allBlocked;
      ready = allBlocked && !store.getBoolean("shutdown_failed", false);
      if (store.getBoolean("shutdown_failed", false))
        return status = "Android shut down Bluetooth before reconnect blocking could finish.";
      if (!allBlocked)
        return status = "Active audio connections are not protected from reconnecting.";
      return status =
          owned.isEmpty()
              ? "No eligible classic-audio devices yet. LE Audio is not covered."
              : "Classic audio connections are blocked. Use Connect to connect a device.";
    } catch (Exception e) {
      ready = false;
      activeConnection = false;
      if (attemptedWrite) return rollback(service, e);
      return status =
          (enabled()
                  ? "Manual connections need attention: "
                  : hasPendingRestore() ? "Restore pending: " : "Connection settings unavailable: ")
              + CodecService.error(e);
    }
  }

  boolean operationFailed() {
    return operationFailed;
  }

  /**
   * Block and verify while the stack is ON, then request shutdown. Never race a shutdown broadcast.
   */
  synchronized String turnBluetoothOff(ICodecService service) {
    operationFailed = true;
    ready = false;
    explicitConnections.clear();
    boolean disableInvoked = false;
    boolean attemptedWrite = false;
    boolean previouslyEnabled = enabled();
    String previousJournal = store.getString("journal", "[]");
    try {
      if (service == null) throw new IllegalStateException("Start Shizuku first");
      JSONObject snapshot = read(service);
      Map<String, JSONObject> actual = index(snapshot.getJSONArray("policies"));
      Map<String, JSONObject> owned = journal();
      int allowed = snapshot.getInt("allowed"), forbidden = snapshot.getInt("forbidden");
      for (JSONObject row : actual.values()) {
        String key = key(row);
        JSONObject entry = owned.get(key);
        int policy = row.getInt("policy");
        if (entry != null && policy == forbidden && !owns(entry, policy)) {
          owned.remove(key);
        } else if (entry == null && policy == allowed) {
          owned.put(
              key, new JSONObject(row.toString()).put("original", policy).put("applied", policy));
        }
      }
      saveJournal(owned);
      if (!store.edit().putBoolean("enabled", true).commit())
        throw new IllegalStateException("Could not save connection settings");
      for (JSONObject entry : owned.values()) {
        JSONObject current = actual.get(key(entry));
        if (current == null) throw new IllegalStateException("Paired device changed; retry");
        attemptedWrite = true;
        transition(service, owned, entry, current.getInt("policy"), forbidden);
      }
      Map<String, JSONObject> verified = index(read(service).getJSONArray("policies"));
      for (JSONObject entry : owned.values()) {
        JSONObject current = verified.get(key(entry));
        if (current == null || current.getInt("policy") != forbidden)
          throw new IllegalStateException("Connection blocking did not remain applied");
      }
      // Also reject newly enabled profiles that appeared between the two snapshots.
      for (JSONObject row : verified.values())
        if (row.getInt("policy") == allowed)
          throw new IllegalStateException("Audio policies changed during verification; retry");
      disableInvoked = true;
      JSONObject result = new JSONObject(service.turnBluetoothOff());
      if (!result.optBoolean("off"))
        throw new IllegalStateException(
            result.optString("error", "Bluetooth OFF was not confirmed"));
      store.edit().remove("shutdown_failed").commit();
      operationFailed = false;
      activeConnection = false;
      ready = true;
      return status = "Bluetooth is off.";
    } catch (Exception e) {
      if (!disableInvoked && attemptedWrite) {
        rollback(service, e);
        return status =
            hasPendingRestore()
                ? "Couldn’t turn Bluetooth off. Tap Restore automatic reconnect to undo the"
                    + " connection changes."
                : "Couldn’t turn Bluetooth off. Previous connection settings were restored.";
      }
      if (!disableInvoked) {
        store
            .edit()
            .putBoolean("enabled", previouslyEnabled)
            .putString("journal", previousJournal)
            .commit();
      }
      return status =
          disableInvoked
              ? "Couldn’t confirm Bluetooth is off. Tap Restore automatic reconnect to undo the"
                  + " connection changes."
              : "Couldn’t turn Bluetooth off. Check that Bluetooth and Shizuku are on, then try"
                  + " again.";
    }
  }

  synchronized boolean connectFromSettings(ICodecService service, String address) {
    if (!enabled() || !ownsDevice(address)) return false;
    connect(service, address);
    return true;
  }

  private boolean ownsDevice(String address) {
    try {
      for (JSONObject row : journal().values())
        if (address.equals(row.optString("address"))) return true;
    } catch (JSONException ignored) {
    }
    return false;
  }

  /** Temporarily permit only this device. Caller should reconcile again after 15 seconds. */
  synchronized String connect(ICodecService service, String address) {
    ready = false;
    if (service == null) return status = "Start Shizuku to connect this device.";
    try {
      JSONObject snapshot = read(service);
      Map<String, JSONObject> actual = index(snapshot.getJSONArray("policies"));
      Map<String, JSONObject> owned = journal();
      explicitConnections.put(address, SystemClock.elapsedRealtime() + 15000);
      int unknown = snapshot.getInt("unknown");
      for (JSONObject entry : owned.values()) {
        if (!address.equals(entry.getString("address"))) continue;
        JSONObject current = actual.get(key(entry));
        if (current == null) continue;
        if (current.getInt("policy") != unknown)
          transition(service, owned, entry, current.getInt("policy"), unknown);
      }
      JSONObject result = new JSONObject(service.connectAudio(address));
      if (!result.optBoolean("requested")) {
        explicitConnections.remove(address);
        reconcile(service);
        return status =
            result.optString(
                "error", "Connection request failed. Check that the device is on and nearby.");
      }
      return status = "Connection requested.";
    } catch (Exception e) {
      explicitConnections.remove(address);
      ready = false;
      return status = "Could not connect: " + CodecService.error(e);
    }
  }

  private void transition(
      ICodecService service,
      Map<String, JSONObject> owned,
      JSONObject entry,
      int before,
      int target)
      throws Exception {
    entry.put("previous", before).put("applied", target);
    saveJournal(owned);
    JSONObject changed =
        new JSONObject(
            service.setAudioPolicy(entry.getString("address"), entry.getInt("profile"), target));
    if (!changed.optBoolean("verified"))
      throw new IllegalStateException(
          changed.optString("error", "Android rejected the policy change"));
    entry.remove("previous");
    saveJournal(owned);
  }

  private String rollback(ICodecService service, Exception failure) {
    store.edit().putBoolean("enabled", false).commit();
    ready = false;
    try {
      JSONObject snapshot = read(service);
      restore(service, index(snapshot.getJSONArray("policies")), journal());
    } catch (Exception unavailable) {
    } // Durable journal remains.
    return status =
        "Manual connections could not be enabled. "
            + (hasPendingRestore()
                ? "Some settings still need restoring; keep Bluetooth and Shizuku on."
                : "Your previous settings were restored.")
            + " Details: "
            + CodecService.error(failure);
  }

  private String restore(
      ICodecService service, Map<String, JSONObject> actual, Map<String, JSONObject> owned)
      throws Exception {
    Map<String, JSONObject> remaining = new LinkedHashMap<>();
    for (JSONObject entry : owned.values()) {
      JSONObject now = actual.get(key(entry));
      if (now == null) continue;
      int policy = now.getInt("policy");
      if (policy == entry.getInt("original") || !owns(entry, policy)) continue;
      JSONObject result =
          new JSONObject(
              service.setAudioPolicy(
                  entry.getString("address"), entry.getInt("profile"), entry.getInt("original")));
      if (!result.optBoolean("verified")) remaining.put(key(entry), entry);
    }
    saveJournal(remaining);
    return status =
        remaining.isEmpty()
            ? "Android’s connection settings are restored."
            : "Some connection settings still need restoring. Keep Bluetooth and Shizuku on, then"
                + " try again.";
  }

  private static boolean owns(JSONObject entry, int policy) {
    return policy == entry.optInt("applied")
        || (entry.has("previous") && policy == entry.optInt("previous"));
  }

  private static JSONObject read(ICodecService service) throws Exception {
    JSONObject result = new JSONObject(service.audioPolicies());
    if (result.has("error")) throw new IllegalStateException(result.getString("error"));
    return result;
  }

  private Map<String, JSONObject> journal() throws JSONException {
    return index(new JSONArray(store.getString("journal", "[]")));
  }

  private void saveJournal(Map<String, JSONObject> rows) throws Exception {
    JSONArray array = new JSONArray();
    for (JSONObject row : rows.values()) array.put(row);
    if (!store.edit().putString("journal", array.toString()).commit())
      throw new IllegalStateException(
          "Could not save the restore journal; no further settings changed");
  }

  private static String key(JSONObject row) {
    return row.optString("address") + "/" + row.optInt("profile");
  }

  private static Map<String, JSONObject> index(JSONArray rows) throws JSONException {
    Map<String, JSONObject> result = new LinkedHashMap<>();
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      result.put(key(row), row);
    }
    return result;
  }
}
