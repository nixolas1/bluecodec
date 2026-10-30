package no.nixo.bluecodec;

import static org.junit.Assert.*;

import android.content.*;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;

/** Tests run with a fake codec boundary, never modifying Bluetooth or real headset preferences. */
public class ControllerTest {
  private CodecController controller;
  private FakeService service;
  private Context context;
  static final String DEVICE = "00:00:00:00:00:01";

  static class FakeService extends ICodecService.Stub {
    final AtomicInteger requests = new AtomicInteger();
    final AtomicInteger powerOffRequests = new AtomicInteger();
    final AtomicInteger policyReads = new AtomicInteger();
    final AtomicInteger outputRequests = new AtomicInteger();
    volatile String output = "phone", outputFailure = "";

    public String inspect() {
      return "{\"soundOutput\":{\"selected\":\""
          + output
          + "\"},\"devices\":[{\"address\":\""
          + DEVICE
          + "\",\"connected\":true,\"current\":{\"type\":4}}]}";
    }

    public String apply(String address, int codec) {
      requests.incrementAndGet();
      return "Codec unavailable or rejected";
    }

    public String audioPolicies() {
      policyReads.incrementAndGet();
      return "{}";
    }

    public String setAudioPolicy(String address, int profile, int policy) {
      return "{}";
    }

    public String manualConnectionRequests(boolean enabled) {
      return "{\"available\":true,\"requests\":[]}";
    }

    public String turnBluetoothOn() {
      return "{\"on\":true}";
    }

    public String turnBluetoothOff() {
      powerOffRequests.incrementAndGet();
      return "{\"accepted\":true,\"off\":true}";
    }

    public String connectAudio(String address) {
      return "{}";
    }

    public void destroy() {}

    public String selectSoundOutput(String target) {
      outputRequests.incrementAndGet();
      if (!outputFailure.isEmpty()) return "{\"error\":\"" + outputFailure + "\"}";
      output = target;
      return "{}";
    }
  }

  void main(Runnable action) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
  }

  @Before
  public void setup() throws Exception {
    context =
        new ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
          public SharedPreferences getSharedPreferences(String name, int mode) {
            return super.getSharedPreferences("isolated_controller_test", mode);
          }
        };
    context.getSharedPreferences("", 0).edit().clear().commit();
    main(
        () -> {
          controller =
              new CodecController() {
                @Override
                boolean authorized() {
                  return true;
                }
              };
          controller.prefs = new Preferences(context);
          controller.reconnect = new ReconnectSettings(context);
          controller.prefs.save(DEVICE, 3, "aptX HD");
          controller.prefs.enable(DEVICE, true);
        });
    service = new FakeService();
    Field f = CodecController.class.getDeclaredField("remote");
    f.setAccessible(true);
    f.set(controller, service);
  }

  @After
  public void cleanup() {
    main(() -> controller.setMonitoring(false));
    context.getSharedPreferences("", 0).edit().clear().commit();
  }

  @Test
  public void outputSelectionHonorsLatestRequest() throws Exception {
    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
    main(
        () -> {
          controller.listeners.add(
              () -> {
                if (controller.pendingOutput.isEmpty()) done.countDown();
              });
          controller.selectSoundOutput(DEVICE);
          controller.selectSoundOutput("phone");
          assertEquals(DEVICE, controller.pendingOutput);
        });
    assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(2, service.outputRequests.get());
    main(
        () ->
            assertEquals(
                "phone", controller.snapshot.optJSONObject("soundOutput").optString("selected")));
  }

  @Test
  public void connectedHeadsetReplacesPhoneOutput() throws Exception {
    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
    main(() -> {
      controller.listeners.add(() -> {
        if (DEVICE.equals(controller.snapshot.optJSONObject("soundOutput") == null
            ? "" : controller.snapshot.optJSONObject("soundOutput").optString("selected")))
          done.countDown();
      });
      controller.connectedEvent(DEVICE);
    });
    assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(DEVICE, service.output);
    assertEquals(1, service.outputRequests.get());
  }

  @Test
  public void rejectedOutputKeepsActualRouteAndExposesError() throws Exception {
    service.outputFailure = "Device disconnected";
    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
    main(
        () -> {
          controller.listeners.add(
              () -> {
                if (controller.pendingOutput.isEmpty()) done.countDown();
              });
          controller.selectSoundOutput(DEVICE);
        });
    assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
    main(
        () -> {
          assertEquals(
              "phone", controller.snapshot.optJSONObject("soundOutput").optString("selected"));
          assertEquals("Device disconnected", controller.outputError);
        });
  }

  @Test
  public void disablingBeforeNegotiationCancelsChanges() throws Exception {
    main(() -> controller.setMonitoring(true));
    Thread.sleep(200);
    main(
        () -> {
          controller.prefs.enable(DEVICE, false);
          controller.changed(DEVICE);
        });
    Thread.sleep(1700);
    assertEquals(0, service.requests.get());
  }

  @Test
  public void disconnectRaceCancelsAndReconnectGetsNewAttempt() throws Exception {
    main(() -> controller.setMonitoring(true));
    Thread.sleep(200);
    main(() -> controller.disconnected(DEVICE));
    Thread.sleep(1700);
    assertEquals(0, service.requests.get());
    main(() -> controller.connectedEvent(DEVICE));
    Thread.sleep(1700);
    assertEquals(1, service.requests.get());
  }

  @Test
  public void duplicateEventsAndRejectionsHaveFiniteBudget() throws Exception {
    main(() -> controller.setMonitoring(true));
    Thread.sleep(200);
    main(
        () -> {
          for (int i = 0; i < 10; i++) controller.schedule(DEVICE);
        });
    Thread.sleep(20000);
    assertEquals(4, service.requests.get());
    main(
        () -> {
          for (int i = 0; i < 10; i++) controller.refresh(false);
        });
    Thread.sleep(1500);
    assertEquals(4, service.requests.get());
    main(() -> assertTrue(controller.results.get(DEVICE).contains("unavailable or rejected")));
  }

  @Test
  public void preferencesStaySeparateAcrossInstances() {
    Preferences original = new Preferences(context);
    original.save("second-device", 1, "AAC");
    original.enable("second-device", false);
    Preferences reopened = new Preferences(context);
    assertEquals(3, reopened.codec(DEVICE));
    assertTrue(reopened.enabled(DEVICE));
    assertEquals(1, reopened.codec("second-device"));
    assertFalse(reopened.enabled("second-device"));
  }

  @Test
  public void manualApplyShowsLoadingAndRejectsDuplicateRequests() throws Exception {
    main(
        () -> {
          controller.manual(DEVICE);
          controller.manual(DEVICE);
          assertTrue(controller.isApplying(DEVICE));
        });
    Thread.sleep(1700);
    assertEquals(1, service.requests.get());
    main(
        () -> {
          assertFalse(controller.isApplying(DEVICE));
          assertTrue(controller.results.get(DEVICE).contains("not confirmed"));
        });
  }

  @Test
  public void tileWithBlockingOffOnlyChangesBluetoothPower() throws Exception {
    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
    main(() -> controller.turnBluetoothOff(done::countDown));
    assertTrue(done.await(3, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(1, service.powerOffRequests.get());
    assertEquals(0, service.policyReads.get());
  }

  @Test
  public void enablingTileModeDoesNotDisconnectOrChangePolicies() {
    main(() -> controller.setPreventReconnect(true, () -> {}));
    assertTrue(controller.preventReconnect());
    assertEquals(0, service.powerOffRequests.get());
    assertEquals(0, service.policyReads.get());
  }

  @Test
  public void tileWithBlockingOnRequiresVerifiedPoliciesBeforePowerOff() throws Exception {
    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
    main(
        () -> {
          controller.setPreventReconnect(true, () -> {});
          controller.turnBluetoothOff(done::countDown);
        });
    assertTrue(done.await(3, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(1, service.policyReads.get());
    assertEquals(0, service.powerOffRequests.get());
    assertTrue(controller.reconnectActionFailed);
  }
}
