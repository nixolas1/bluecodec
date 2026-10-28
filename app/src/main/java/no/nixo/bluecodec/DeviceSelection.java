package no.nixo.bluecodec;

import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Follow connection changes without overriding an explicit choice on every refresh. */
final class DeviceSelection {
  private Set<String> connected = new HashSet<>();
  private String active = "";
  private boolean opening = true;

  void opened() {
    opening = true;
  }

  String update(JSONObject state, String selected) {
    JSONArray devices = state.optJSONArray("devices");
    if (state.has("error") || devices == null) return selected;
    Set<String> now = new HashSet<>();
    String first = "", newlyConnected = "", routed = "";
    boolean selectionExists = false;
    for (int i = 0; i < devices.length(); i++) {
      JSONObject device = devices.optJSONObject(i);
      if (device == null) continue;
      String address = device.optString("address");
      if (address.isEmpty()) continue;
      if (first.isEmpty()) first = address;
      selectionExists |= address.equals(selected);
      if (!device.optBoolean("connected")) continue;
      now.add(address);
      if (!connected.contains(address) && newlyConnected.isEmpty()) newlyConnected = address;
      if (device.optBoolean("active")) routed = address;
    }
    if (!routed.isEmpty() && (opening || !routed.equals(active))) selected = routed;
    else if (!newlyConnected.isEmpty()) selected = newlyConnected;
    else if ((opening || connected.contains(selected))
        && !now.contains(selected)
        && !now.isEmpty()) {
      // Keep snapshot order as the deterministic fallback when the platform has no active route.
      for (int i = 0; i < devices.length(); i++) {
        JSONObject device = devices.optJSONObject(i);
        if (device != null && now.contains(device.optString("address"))) {
          selected = device.optString("address");
          break;
        }
      }
    } else if (!selectionExists && !first.isEmpty()) selected = first;
    connected = now;
    active = routed;
    // An empty startup snapshot must not consume the preference for a connected device.
    if (!now.isEmpty()) opening = false;
    return selected;
  }
}
