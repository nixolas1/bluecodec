package no.nixo.bluecodec;

import static org.junit.Assert.*;

import java.util.Arrays;
import org.junit.Test;

public class ManualConnectionRequestsTest {
  private static final String LINE =
      "         1793047567.533  1002 12477 12557 I BluetoothAdapterServiceBinder:"
          + " connectAllEnabledProfiles: device=XX:XX:XX:XX:69:B6, from uid/pid=1000/3629";

  @Test
  public void parsesPixelEventWithProducerAndCallerIdentity() {
    ManualConnectionRequests.Event e = ManualConnectionRequests.parse(LINE);
    assertNotNull(e);
    assertEquals(1793047567533L, e.time);
    assertEquals(1002, e.producerUid);
    assertEquals(12477, e.producerPid);
    assertEquals(1000, e.callerUid);
    assertEquals(3629, e.callerPid);
    assertEquals("XX:XX:XX:XX:69:B6", e.device);
  }

  @Test
  public void rejectsWrongTagAndEmbeddedForgedLine() {
    assertNull(
        ManualConnectionRequests.parse(LINE.replace("BluetoothAdapterServiceBinder", "OtherTag")));
    assertNull(ManualConnectionRequests.parse("untrusted text " + LINE));
    assertNull(ManualConnectionRequests.parse(LINE + " extra"));
    assertNull(
        ManualConnectionRequests.parse(
            LINE.replace("connectAllEnabledProfiles", "disconnectAllEnabledProfiles")));
  }

  @Test
  public void missingUidIsNotAccepted() {
    assertNull(ManualConnectionRequests.parse(LINE.replace("1002 12477", "12477")));
  }

  @Test
  public void uniqueRedactedAddressResolvesToBondedDeviceOnly() {
    assertEquals(
        "AA:BB:CC:DD:69:B6",
        ManualConnectionRequests.resolve(
            "XX:XX:XX:XX:69:B6", Arrays.asList("AA:BB:CC:DD:69:B6", "AA:BB:CC:DD:12:34")));
    assertNull(
        ManualConnectionRequests.resolve("XX:XX:XX:XX:69:B6", Arrays.asList("AA:BB:CC:DD:12:34")));
  }

  @Test
  public void suffixCollisionFailsClosed() {
    assertNull(
        ManualConnectionRequests.resolve(
            "XX:XX:XX:XX:69:B6", Arrays.asList("AA:BB:CC:DD:69:B6", "12:34:56:78:69:B6")));
  }

  @Test
  public void exactAddressAndMalformedRedaction() {
    assertEquals(
        "AA:BB:CC:DD:69:B6",
        ManualConnectionRequests.resolve("AA:BB:CC:DD:69:B6", Arrays.asList("AA:BB:CC:DD:69:B6")));
    assertNull(ManualConnectionRequests.resolve("69:B6", Arrays.asList("AA:BB:CC:DD:69:B6")));
    assertNull(
        ManualConnectionRequests.resolve("XX:XX:XX:69:B6", Arrays.asList("AA:BB:CC:DD:69:B6")));
  }

  @Test
  public void historicExpiredAndFutureEventsFailClosed() {
    assertFalse(ManualConnectionRequests.fresh(999, 1000, 1001));
    assertFalse(ManualConnectionRequests.fresh(1000, 1000, 6001));
    assertFalse(ManualConnectionRequests.fresh(1002, 1000, 1001));
    assertTrue(ManualConnectionRequests.fresh(1000, 1000, 1001));
  }
}
