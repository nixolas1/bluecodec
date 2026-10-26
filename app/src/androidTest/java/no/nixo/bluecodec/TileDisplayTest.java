package no.nixo.bluecodec;

import static org.junit.Assert.*;

import android.bluetooth.BluetoothAdapter;
import android.service.quicksettings.Tile;
import org.json.*;
import org.junit.Test;

public class TileDisplayTest {
  private JSONObject snapshot(int battery, int count) throws Exception {
    JSONArray devices = new JSONArray();
    for (int i = 0; i < count; i++)
      devices.put(new JSONObject().put("name", "Nico's Sony").put("battery", battery));
    return new JSONObject().put("bluetoothEnabled", true).put("tileDevices", devices);
  }

  private TileDisplay on(JSONObject data) {
    return TileDisplay.from(BluetoothAdapter.STATE_ON, false, true, true, data);
  }

  @Test
  public void singleDeviceUsesAliasAndBattery() throws Exception {
    TileDisplay d = on(snapshot(70, 1));
    assertEquals("Nico's Sony", d.label);
    assertEquals("70% battery", d.subtitle);
    assertEquals(Tile.STATE_ACTIVE, d.state);
  }

  @Test
  public void zeroBatteryIsValid() throws Exception {
    assertEquals("0% battery", on(snapshot(0, 1)).subtitle);
  }

  @Test
  public void missingBatteryNeverInventsPercentage() throws Exception {
    assertEquals("Connected", on(snapshot(-1, 1)).subtitle);
    assertEquals("Connected", on(snapshot(101, 1)).subtitle);
  }

  @Test
  public void multipleDevicesUseCount() throws Exception {
    TileDisplay d = on(snapshot(70, 2));
    assertEquals("Bluetooth", d.label);
    assertEquals("2 devices", d.subtitle);
    assertEquals("3 devices", on(snapshot(70, 3)).subtitle);
  }

  @Test
  public void offIgnoresStaleConnectedSnapshot() throws Exception {
    TileDisplay d =
        TileDisplay.from(BluetoothAdapter.STATE_OFF, false, true, true, snapshot(70, 1));
    assertEquals("Bluetooth", d.label);
    assertEquals("Off", d.subtitle);
    assertEquals(Tile.STATE_INACTIVE, d.state);
  }

  @Test
  public void transitionDisablesRepeatedTaps() throws Exception {
    assertEquals(
        Tile.STATE_UNAVAILABLE,
        TileDisplay.from(BluetoothAdapter.STATE_ON, true, true, true, snapshot(70, 1)).state);
    assertEquals(
        "Turning on…",
        TileDisplay.from(BluetoothAdapter.STATE_TURNING_ON, false, true, true, snapshot(70, 1))
            .subtitle);
  }

  @Test
  public void missingShizukuOffersAppInsteadOfFalseSuccess() throws Exception {
    assertEquals(
        "Open BlueCodec",
        TileDisplay.from(BluetoothAdapter.STATE_ON, false, false, false, snapshot(70, 1)).subtitle);
  }
}
