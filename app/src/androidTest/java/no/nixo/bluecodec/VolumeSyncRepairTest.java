package no.nixo.bluecodec;

import static org.junit.Assert.*;

import org.junit.Test;

public class VolumeSyncRepairTest {
  private static final String ADDRESS = "11:22:33:44:DD:B9";
  private static final String DUMP =
      "AvrcpVolumeManager:\n"
          + "    mCurrentDevice: null\n"
          + "    Current System Volume: 12\n"
          + "    Device Absolute Volume Map:\n"
          + "  XX:XX:XX:XX:DD:B9: true\n"
          + "    BTAudio Volume Events:\n"
          + "old log\n"
          + "AVRCP Target Native Service: 1 devices\n"
          + "  Absolute Volume Enabled: true\n\n"
          + "    xx:xx:xx:xx:dd:b9 <Active>\n"
          + "    Current Volume: 41\n"
          + "    Current Browsed Player ID: 0\n";

  @Test
  public void detectsObservedMissingRegistration() {
    assertEquals(41, VolumeSyncRepair.remoteVolume(DUMP, ADDRESS));
  }

  @Test
  public void respectsDisabledAbsoluteVolume() {
    assertEquals(
        -1,
        VolumeSyncRepair.remoteVolume(DUMP.replace("Enabled: true", "Enabled: false"), ADDRESS));
    assertEquals(
        -1, VolumeSyncRepair.remoteVolume(DUMP.replace("DD:B9: true", "DD:B9: false"), ADDRESS));
  }

  @Test
  public void rejectsHealthyManagerAndOtherActiveDevice() {
    assertEquals(
        -1,
        VolumeSyncRepair.remoteVolume(
            DUMP.replace("mCurrentDevice: null", "mCurrentDevice: XX:XX:XX:XX:DD:B9"), ADDRESS));
    assertEquals(
        -1,
        VolumeSyncRepair.remoteVolume(DUMP.replace("dd:b9 <Active>", "aa:bb <Active>"), ADDRESS));
  }

  @Test
  public void rejectsAmbiguousOrUnknownDiagnostics() {
    assertEquals(-1, VolumeSyncRepair.remoteVolume("", ADDRESS));
    assertEquals(-1, VolumeSyncRepair.remoteVolume(DUMP, "invalid"));
    assertEquals(
        -1, VolumeSyncRepair.remoteVolume(DUMP.replace("1 devices", "2 devices"), ADDRESS));
    assertEquals(
        -1,
        VolumeSyncRepair.remoteVolume(
            DUMP.replace("Current Volume: 41", "Current Volume: 128"), ADDRESS));
    assertEquals(
        -1,
        VolumeSyncRepair.remoteVolume(
            DUMP.replace(
                "Device Absolute Volume Map:",
                "Device Absolute Volume Map:\n  11:22:33:44:DD:B9: true"),
            ADDRESS));
  }

  @Test
  public void repairNeverRaisesEitherVolume() {
    assertEquals(8, VolumeSyncRepair.safeVolume(25, 41, 25));
    assertEquals(3, VolumeSyncRepair.safeVolume(3, 41, 25));
    assertEquals(0, VolumeSyncRepair.safeVolume(0, 41, 25));
    assertEquals(0, VolumeSyncRepair.safeVolume(25, 0, 25));
    assertEquals(-1, VolumeSyncRepair.safeVolume(25, -1, 25));
    assertEquals(-1, VolumeSyncRepair.safeVolume(25, 128, 25));
  }
}
