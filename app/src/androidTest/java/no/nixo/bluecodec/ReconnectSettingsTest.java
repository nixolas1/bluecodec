package no.nixo.bluecodec;

import static org.junit.Assert.*;

import android.content.*;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
import org.json.*;
import org.junit.*;

/**
 * Isolated policy journal tests. They never use the phone's Bluetooth service or real preferences.
 */
public class ReconnectSettingsTest {
  private Context context;
  private FakeAudio service;
  private ReconnectSettings settings;

  @Before
  public void setup() {
    context =
        new ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
          @Override
          public SharedPreferences getSharedPreferences(String name, int mode) {
            return super.getSharedPreferences("isolated_reconnect_test", mode);
          }
        };
    context.getSharedPreferences("", 0).edit().clear().commit();
    settings = new ReconnectSettings(context);
    service = new FakeAudio();
  }

  @After
  public void cleanup() {
    context.getSharedPreferences("", 0).edit().clear().commit();
  }

  class FakeAudio extends ICodecService.Stub {
    final Map<Integer, Integer> policies = new LinkedHashMap<>();
    int reject = -1;
    boolean throwOnSecond;
    boolean connected = true;
    boolean rejectForbidden;
    boolean disableCalled;
    int connectionRequests;
    boolean failDisable;
    boolean driftAfterWrite;
    boolean sawJournalBeforeMutation;

    FakeAudio() {
      policies.put(2, 100);
      policies.put(1, 100);
      policies.put(3, 0);
    }

    public String inspect() {
      return "{}";
    }

    public String apply(String address, int codec) {
      return "";
    }

    public void destroy() {}

    public String selectSoundOutput(String target) {
      return "{}";
    }

    public String audioPolicies() {
      if (driftAfterWrite && policies.get(1) == 0) policies.put(1, 100);
      try {
        JSONArray rows = new JSONArray();
        for (Map.Entry<Integer, Integer> e : policies.entrySet())
          rows.put(
              new JSONObject()
                  .put("address", "device")
                  .put("profile", e.getKey())
                  .put("policy", e.getValue())
                  .put("connected", connected));
        return new JSONObject()
            .put("allowed", 100)
            .put("unknown", -1)
            .put("forbidden", 0)
            .put("policies", rows)
            .toString();
      } catch (JSONException e) {
        throw new RuntimeException(e);
      }
    }

    public String setAudioPolicy(String address, int profile, int policy) {
      sawJournalBeforeMutation |=
          !"[]".equals(context.getSharedPreferences("", 0).getString("journal", "[]"));
      if (throwOnSecond && profile == 1 && policy == -1)
        throw new IllegalStateException("Service interrupted");
      if (rejectForbidden && profile == 1 && policy == 0)
        return "{\"verified\":false,\"error\":\"Service stopped\"}";
      if (profile == reject && policy == -1) return "{\"verified\":false,\"error\":\"Denied\"}";
      policies.put(profile, policy);
      return "{\"verified\":true}";
    }

    public String manualConnectionRequests(boolean enabled) {
      return "{\"available\":true,\"requests\":[]}";
    }

    public String turnBluetoothOn() {
      return "{\"on\":true}";
    }

    public String turnBluetoothOff() {
      assertEquals(Integer.valueOf(0), policies.get(2));
      assertEquals(Integer.valueOf(0), policies.get(1));
      disableCalled = true;
      return failDisable ? "{\"off\":false,\"error\":\"Timeout\"}" : "{\"off\":true}";
    }

    public String connectAudio(String address) {
      connectionRequests++;
      return "{\"requested\":true}";
    }
  }

  @Test
  public void togglingWhileConnectedIsALimitationNotShutdownFailure() {
    settings.setEnabled(true, service);
    assertTrue(settings.limitedByActiveConnection());
    assertFalse(settings.ready());
    settings.setEnabled(false, service);
    settings.setEnabled(true, service);
    assertTrue(settings.limitedByActiveConnection());
    assertFalse(context.getSharedPreferences("", 0).getBoolean("shutdown_failed", false));
  }

  @Test
  public void journalsBeforeWritingAndPreservesUserDisabledProfiles() {
    settings.setEnabled(true, service);
    assertTrue(service.sawJournalBeforeMutation);
    assertEquals(Integer.valueOf(-1), service.policies.get(2));
    assertEquals(Integer.valueOf(-1), service.policies.get(1));
    assertEquals(Integer.valueOf(0), service.policies.get(3));
    assertTrue(settings.enabled());
  }

  @Test
  public void disablingAfterProcessRecreationRestoresOriginalPolicies() {
    settings.setEnabled(true, service);
    ReconnectSettings recreated = new ReconnectSettings(context);
    assertTrue(recreated.enabled());
    assertTrue(recreated.hasPendingRestore());
    recreated.setEnabled(false, service);
    assertEquals(Integer.valueOf(100), service.policies.get(2));
    assertEquals(Integer.valueOf(100), service.policies.get(1));
    assertFalse(recreated.hasPendingRestore());
  }

  @Test
  public void serviceDeathRetainsRestoreJournalUntilRecovery() {
    settings.setEnabled(true, service);
    settings.setEnabled(false, null);
    assertFalse(settings.enabled());
    assertTrue(settings.hasPendingRestore());
    assertTrue(settings.needsService());
    settings.reconcile(service);
    assertFalse(settings.hasPendingRestore());
    assertFalse(settings.needsService());
  }

  @Test
  public void laterExternalPolicyChangeIsNeverOverwritten() {
    settings.setEnabled(true, service);
    service.policies.put(2, 0);
    settings.setEnabled(false, service);
    assertEquals(Integer.valueOf(0), service.policies.get(2));
    assertEquals(Integer.valueOf(100), service.policies.get(1));
    assertFalse(settings.hasPendingRestore());
  }

  @Test
  public void partialFailureRollsBackAndDoesNotClaimEnabled() {
    service.reject = 1;
    String status = settings.setEnabled(true, service);
    assertFalse(settings.enabled());
    assertEquals(Integer.valueOf(100), service.policies.get(2));
    assertEquals(Integer.valueOf(100), service.policies.get(1));
    assertFalse(settings.hasPendingRestore());
    assertTrue(status.contains("could not be enabled"));
  }

  @Test
  public void exceptionDuringMutationDisablesAndRestores() {
    service.throwOnSecond = true;
    settings.setEnabled(true, service);
    assertFalse(settings.enabled());
    assertFalse(settings.ready());
    assertEquals(Integer.valueOf(100), service.policies.get(2));
    assertFalse(settings.hasPendingRestore());
  }

  @Test
  public void connectedProfileReenabledByAndroidIsRearmed() {
    settings.setEnabled(true, service);
    service.policies.put(2, 100);
    settings.reconcile(service);
    assertEquals(Integer.valueOf(-1), service.policies.get(2));
    settings.setEnabled(false, service);
    assertEquals(Integer.valueOf(100), service.policies.get(2));
  }

  @Test
  public void disconnectedProfilesAreBlockedWithoutWaitingForRadioOff() {
    service.connected = false;
    settings.setEnabled(true, service);
    assertEquals(Integer.valueOf(0), service.policies.get(2));
    assertEquals(Integer.valueOf(0), service.policies.get(1));
    assertTrue(settings.ready());
  }

  @Test
  public void turningOffBlocksOnlyPreviouslyActiveProfilesAndCanRestore() {
    settings.setEnabled(true, service);
    assertEquals(Integer.valueOf(-1), service.policies.get(2));
    settings.turnBluetoothOff(service);
    assertEquals(Integer.valueOf(0), service.policies.get(2));
    assertEquals(Integer.valueOf(0), service.policies.get(1));
    assertTrue(settings.ready());
    settings.setEnabled(false, service);
    assertEquals(Integer.valueOf(100), service.policies.get(2));
    assertEquals(Integer.valueOf(100), service.policies.get(1));
  }

  @Test
  public void explicitConnectOpensOnlyJournaledAudioPolicies() {
    service.connected = false;
    settings.setEnabled(true, service);
    settings.connect(service, "device");
    assertFalse(settings.ready());
    assertEquals(Integer.valueOf(-1), service.policies.get(2));
    assertEquals(Integer.valueOf(-1), service.policies.get(1));
    assertEquals(Integer.valueOf(0), service.policies.get(3));
    settings.reconcile(service);
    assertEquals(Integer.valueOf(-1), service.policies.get(2));
  }

  @Test
  public void failedBlockingRestoresAndNeverDisablesBluetooth() {
    settings.setEnabled(true, service);
    service.rejectForbidden = true;
    settings.turnBluetoothOff(service);
    assertTrue(settings.operationFailed());
    assertFalse(service.disableCalled);
    assertEquals(Integer.valueOf(100), service.policies.get(2));
    assertEquals(Integer.valueOf(100), service.policies.get(1));
    assertFalse(settings.hasPendingRestore());
  }

  @Test
  public void fullReadbackFailureNeverDisablesBluetooth() {
    service.driftAfterWrite = true;
    settings.turnBluetoothOff(service);
    assertTrue(settings.operationFailed());
    assertFalse(service.disableCalled);
    assertEquals(Integer.valueOf(100), service.policies.get(2));
  }

  @Test
  public void uncertainShutdownRetainsOriginalPoliciesForRestore() {
    service.failDisable = true;
    settings.turnBluetoothOff(service);
    assertTrue(service.disableCalled);
    assertTrue(settings.operationFailed());
    assertTrue(settings.hasPendingRestore());
    new ReconnectSettings(context).setEnabled(false, service);
    assertEquals(Integer.valueOf(100), service.policies.get(2));
    assertEquals(Integer.valueOf(100), service.policies.get(1));
  }

  @Test
  public void directShutdownWorksWithoutPreviouslyEnabledSetting() {
    settings.turnBluetoothOff(service);
    assertTrue(service.disableCalled);
    assertFalse(settings.operationFailed());
    assertTrue(settings.ready());
    assertTrue(settings.hasPendingRestore());
  }

  @Test
  public void activeUnknownPoliciesNeverClaimReconnectPreventionReady() {
    settings.setEnabled(true, service);
    assertFalse(settings.ready());
    assertTrue(settings.limitedByActiveConnection());
    assertEquals(Integer.valueOf(-1), service.policies.get(2));
    settings.reconcile(service);
    assertFalse(settings.ready());
  }

  @Test
  public void missingServiceDoesNotEnableSettingOrDisableBluetooth() {
    settings.turnBluetoothOff(null);
    assertTrue(settings.operationFailed());
    assertFalse(settings.enabled());
    assertFalse(settings.hasPendingRestore());
    assertFalse(service.disableCalled);
  }

  @Test
  public void settingsRequestUnblocksOnlyOwnedDevice() {
    service.connected = false;
    settings.setEnabled(true, service);
    assertEquals(Integer.valueOf(0), service.policies.get(2));
    assertTrue(settings.connectFromSettings(service, "device"));
    assertEquals(Integer.valueOf(-1), service.policies.get(2));
    assertEquals(Integer.valueOf(0), service.policies.get(3));
    assertEquals(1, service.connectionRequests);
  }

  @Test
  public void settingsRequestCannotOpenUnownedDeviceOrDisabledFeature() {
    assertFalse(settings.connectFromSettings(service, "device"));
    settings.setEnabled(true, service);
    assertFalse(settings.connectFromSettings(service, "other device"));
    settings.setEnabled(false, service);
    assertFalse(settings.connectFromSettings(service, "device"));
    assertEquals(0, service.connectionRequests);
  }
}
