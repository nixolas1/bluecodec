package no.nixo.bluecodec;

import android.bluetooth.BluetoothAdapter;
import android.service.quicksettings.Tile;
import org.json.JSONArray;
import org.json.JSONObject;

/** Presentation separated from Android lifecycle so stale/unknown data is testable. */
final class TileDisplay {
  final String label, subtitle;
  final int state;

  private TileDisplay(String label, String subtitle, int state) {
    this.label = label;
    this.subtitle = subtitle;
    this.state = state;
  }

  static TileDisplay from(
      int power, boolean busy, boolean ready, boolean authorized, JSONObject snapshot) {
    if (busy
        || power == BluetoothAdapter.STATE_TURNING_ON
        || power == BluetoothAdapter.STATE_TURNING_OFF)
      return new TileDisplay(
          "Bluetooth",
          power == BluetoothAdapter.STATE_TURNING_ON
              ? "Turning on…"
              : power == BluetoothAdapter.STATE_TURNING_OFF ? "Turning off…" : "Please wait…",
          Tile.STATE_UNAVAILABLE);
    int state = power == BluetoothAdapter.STATE_ON ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
    if (power == BluetoothAdapter.ERROR)
      return new TileDisplay("Bluetooth", "Open BlueCodec", Tile.STATE_INACTIVE);
    if (!ready)
      return new TileDisplay("Bluetooth", authorized ? "Connecting…" : "Open BlueCodec", state);
    if (power == BluetoothAdapter.STATE_OFF)
      return new TileDisplay("Bluetooth", "Off", Tile.STATE_INACTIVE);
    JSONArray devices = snapshot.optJSONArray("tileDevices");
    int count = devices == null || !snapshot.optBoolean("bluetoothEnabled") ? 0 : devices.length();
    if (count > 1) return new TileDisplay("Bluetooth", count + " devices", state);
    if (count == 1) {
      JSONObject device = devices.optJSONObject(0);
      int battery = device.optInt("battery", -1);
      return new TileDisplay(
          device.optString("name", "Bluetooth"),
          battery >= 0 && battery <= 100 ? battery + "% battery" : "Connected",
          state);
    }
    return new TileDisplay("Bluetooth", "On", state);
  }
}
