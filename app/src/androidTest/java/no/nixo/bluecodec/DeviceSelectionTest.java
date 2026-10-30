package no.nixo.bluecodec;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

public class DeviceSelectionTest {
  private JSONObject state(String... rows) throws Exception {
    JSONArray devices = new JSONArray();
    for (String row : rows) {
      String[] parts = row.split(":");
      devices.put(
          new JSONObject()
              .put("address", parts[0])
              .put("connected", parts.length > 1)
              .put("active", parts.length > 2));
    }
    return new JSONObject().put("devices", devices);
  }

  @Test
  public void openingPrefersConnectedOverSavedDisconnected() throws Exception {
    assertEquals("B", new DeviceSelection().update(state("A", "B:on"), "A"));
  }

  @Test
  public void openingPrefersActiveAmongMultipleConnections() throws Exception {
    assertEquals("B", new DeviceSelection().update(state("A:on", "B:on:active"), "A"));
  }

  @Test
  public void retainsConfiguredDeviceWhenAnotherConnects() throws Exception {
    DeviceSelection selection = new DeviceSelection();
    selection.update(state("A:on:active", "B"), "A");
    assertEquals("A", selection.update(state("A:on:active", "B:on"), "A"));
  }

  @Test
  public void configuringDeviceIsIndependentOfPlaybackRoute() throws Exception {
    DeviceSelection selection = new DeviceSelection();
    selection.update(state("A:on:active", "B:on"), "A");
    assertEquals("A", selection.update(state("A:on", "B:on:active"), "A"));
  }

  @Test
  public void manualConfigurationSurvivesIdenticalRefresh() throws Exception {
    DeviceSelection selection = new DeviceSelection();
    JSONObject devices = state("A:on:active", "B:on");
    selection.update(devices, "A");
    assertEquals("B", selection.update(devices, "B"));
    selection.opened();
    assertEquals("A", selection.update(devices, "B"));
  }

  @Test
  public void disconnectedSelectionFallsBackToRemainingConnection() throws Exception {
    DeviceSelection selection = new DeviceSelection();
    selection.update(state("A:on", "B:on"), "A");
    assertEquals("B", selection.update(state("A", "B:on"), "A"));
  }

  @Test
  public void emptyAndErrorSnapshotsDoNotConsumeStartupSelection() throws Exception {
    DeviceSelection selection = new DeviceSelection();
    assertEquals("A", selection.update(state(), "A"));
    assertEquals("A", selection.update(new JSONObject().put("error", "not ready"), "A"));
    assertEquals("B", selection.update(state("A", "B:on"), "A"));
  }

  @Test
  public void retainsOfflineSelectionAndFallsBackWhenRemoved() throws Exception {
    DeviceSelection selection = new DeviceSelection();
    assertEquals("B", selection.update(state("A", "B"), "B"));
    assertEquals("A", selection.update(state("A"), "B"));
  }
}
