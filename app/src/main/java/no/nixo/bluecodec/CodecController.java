package no.nixo.bluecodec;

import android.app.Application;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import rikka.shizuku.Shizuku;

public class CodecController extends Application {
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Map<String, RetryBudget> pending = new HashMap<>();
  private final Set<String> connected = new HashSet<>();
  private final Set<String> disconnecting = new HashSet<>();
  private long generation;
  private int bindAttempts;
  private boolean binding;
  private volatile ICodecService remote;
  Preferences prefs;
  ReconnectSettings reconnect;
  boolean reconnectBusy;
  String pendingOutput = "", outputError = "";
  String reconnectActionMessage = "";
  boolean reconnectActionFailed;
  boolean serviceRunning;
  String manualConnectionError = "";
  private boolean checkingManualConnections;
  private final Map<String, Object> manualSessions = new ConcurrentHashMap<>();
  JSONObject snapshot = new JSONObject();
  String readiness = "Start Shizuku to access headset codecs.";
  final Map<String, String> results = new HashMap<>();
  final Set<Runnable> listeners = new HashSet<>();
  volatile boolean monitoring;
  private boolean refreshing;
  private final Shizuku.UserServiceArgs args =
      new Shizuku.UserServiceArgs(
              new ComponentName("no.nixo.bluecodec", CodecService.class.getName()))
          .daemon(false)
          .processNameSuffix("codec")
          .version(15);
  private final ServiceConnection connection =
      new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
          remote = ICodecService.Stub.asInterface(binder);
          binding = false;
          bindAttempts = 0;
          readiness = "Shizuku connected";
          try {
            binder.linkToDeath(
                () ->
                    main.post(
                        () -> {
                          if (remote != null && remote.asBinder() == binder)
                            lost("Codec service stopped. Reconnecting…");
                        }),
                0);
          } catch (RemoteException e) {
            lost("Codec service stopped");
            return;
          }
          main.postDelayed(
              () -> {
                refresh(true);
                reconcileReconnect();
              },
              700);
          publish();
        }

        public void onServiceDisconnected(ComponentName name) {
          lost("Codec service disconnected. Reconnecting…");
        }
      };

  public void onCreate() {
    super.onCreate();
    prefs = new Preferences(this);
    reconnect = new ReconnectSettings(this);
    if (!prefs.store.contains("preventReconnect"))
      prefs.store.edit().putBoolean("preventReconnect", reconnect.enabled()).apply();
    Shizuku.addBinderReceivedListenerSticky(
        () ->
            main.post(
                () -> {
                  bindAttempts = 0;
                  connect();
                }));
    Shizuku.addBinderDeadListener(
        () -> main.post(() -> lost("Shizuku stopped. Start it again to resume switching.")));
    Shizuku.addRequestPermissionResultListener(
        (code, result) ->
            main.post(
                () -> {
                  if (result == PackageManager.PERMISSION_GRANTED) connect();
                  else {
                    readiness = "Shizuku permission denied. Allow BlueCodec in Shizuku.";
                    publish();
                  }
                }));
  }

  void publish() {
    for (Runnable listener : new ArrayList<>(listeners)) listener.run();
  }

  boolean authorized() {
    try {
      return Shizuku.pingBinder()
          && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    } catch (Exception e) {
      return false;
    }
  }

  void connect() {
    if (remote != null) {
      refresh(false);
      return;
    }
    if (binding) return;
    try {
      if (!Shizuku.pingBinder()) {
        readiness = "Shizuku is stopped. Start it in the Shizuku app.";
        publish();
        return;
      }
      if (!authorized()) {
        readiness = "Allow BlueCodec to use Shizuku.";
        publish();
        return;
      }
      binding = true;
      readiness = "Connecting to Shizuku…";
      publish();
      Shizuku.bindUserService(args, connection);
      main.postDelayed(
          () -> {
            if (binding) {
              binding = false;
              readiness = "Shizuku connection timed out. Tap Connect to retry.";
              publish();
            }
          },
          8000);
    } catch (Exception e) {
      binding = false;
      readiness = CodecService.error(e);
      publish();
    }
  }

  void requestAccess() {
    try {
      if (!Shizuku.pingBinder()) {
        connect();
        return;
      }
      if (!authorized()) Shizuku.requestPermission(1);
      else connect();
    } catch (Exception e) {
      readiness = CodecService.error(e);
      publish();
    }
  }

  private void lost(String reason) {
    remote = null;
    manualSessions.clear();
    binding = false;
    cancelAll();
    connected.clear();
    snapshot = new JSONObject();
    readiness = reason;
    publish();
    if (monitoring && authorized() && bindAttempts++ < 3) main.postDelayed(this::connect, 2000);
  }

  void refresh(boolean reapply) {
    if (!authorized()) {
      if (remote != null) lost("Shizuku permission lost. Restore access to continue.");
      else connect();
      return;
    }
    if (remote == null) {
      connect();
      return;
    }
    if (refreshing) {
      if (reapply) main.postDelayed(() -> refresh(true), 500);
      return;
    }
    refreshing = true;
    ICodecService service = remote;
    worker.execute(
        () -> {
          try {
            JSONObject data = new JSONObject(service.inspect());
            main.post(
                () -> {
                  refreshing = false;
                  if (service != remote) return;
                  snapshot = data;
                  rememberLastConnected(data);
                  readiness =
                      data.has("error")
                          ? data.optString("error")
                          : "Shizuku connected · shell access";
                  Set<String> now = new HashSet<>();
                  JSONArray devices = data.optJSONArray("devices");
                  if (devices != null)
                    for (int i = 0; i < devices.length(); i++) {
                      JSONObject d = devices.optJSONObject(i);
                      if (d.optBoolean("connected")) {
                        String address = d.optString("address");
                        now.add(address);
                        if (!connected.contains(address)) results.remove(address);
                        JSONObject actual = d.optJSONObject("current");
                        if (actual != null
                            && actual.optInt("type", -1) != prefs.codec(address)
                            && results.getOrDefault(address, "").startsWith("Confirmed:"))
                          results.remove(address);
                        if (monitoring
                            && !disconnecting.contains(address)
                            && (!connected.contains(address) || reapply)) schedule(address);
                      }
                    }
                  for (String address : new HashSet<>(connected))
                    if (!now.contains(address)) {
                      cancel(address);
                      results.put(address, "Disconnected. Preference saved.");
                    }
                  connected.clear();
                  connected.addAll(now);
                  publish();
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  refreshing = false;
                  if (service == remote) lost(CodecService.error(e));
                });
          }
        });
  }

  int connectedCount() {
    return connected.size();
  }

  void connectedEvent(String address) {
    if (disconnecting.remove(address)) connected.remove(address);
    refresh(false);
  }

  void disconnected(String address) {
    disconnecting.add(address);
    connected.remove(address);
    cancel(address);
    results.put(address, "Disconnected. Preference saved.");
    publish();
    refresh(false);
  }

  void schedule(String address) {
    if (!monitoring
        || !prefs.enabled(address)
        || prefs.codec(address) < 0
        || pending.containsKey(address)) return;
    RetryBudget budget = new RetryBudget(++generation);
    pending.put(address, budget);
    results.put(address, "Waiting for headset codec negotiation…");
    queue(address, budget);
  }

  private void cancel(String address) {
    RetryBudget old = pending.remove(address);
    if (old != null) old.canceled = true;
  }

  private void cancelAll() {
    for (RetryBudget old : pending.values()) old.canceled = true;
    pending.clear();
  }

  private void queue(String address, RetryBudget budget) {
    if (!budget.available()) {
      main.postDelayed(() -> verifyLast(address, budget), 1200);
      return;
    }
    main.postDelayed(() -> attempt(address, budget), budget.nextDelay());
  }

  private boolean valid(String address, RetryBudget budget) {
    return !budget.canceled
        && pending.get(address) == budget
        && monitoring
        && prefs.enabled(address)
        && remote != null;
  }

  private void attempt(String address, RetryBudget budget) {
    if (!valid(address, budget)) return;
    int desired = prefs.codec(address);
    ICodecService service = remote;
    worker.execute(
        () -> {
          try {
            JSONObject state = new JSONObject(service.inspect());
            JSONObject device = find(state, address);
            boolean matches =
                device != null
                    && device.optJSONObject("current") != null
                    && device.optJSONObject("current").optInt("type", -1) == desired;
            main.post(
                () -> {
                  if (!valid(address, budget)
                      || service != remote
                      || prefs.codec(address) != desired) return;
                  snapshot = state;
                  if (matches) {
                    cancel(address);
                    results.put(address, "Confirmed: " + prefs.codecName(address));
                    publish();
                    return;
                  }
                  if (device == null || !device.optBoolean("connected")) {
                    cancel(address);
                    results.put(address, "Disconnected. Preference saved.");
                    publish();
                    return;
                  }
                  // Queue the mutation only after a fresh main-thread cancellation check.
                  worker.execute(
                      () -> {
                        String result;
                        synchronized (prefs) {
                          if (budget.canceled
                              || !monitoring
                              || !prefs.enabled(address)
                              || prefs.codec(address) != desired
                              || remote != service) return;
                          try {
                            result = service.apply(address, desired);
                          } catch (Exception e) {
                            result = CodecService.error(e);
                          }
                        }
                        String message = result;
                        main.post(
                            () -> {
                              if (!valid(address, budget)) return;
                              results.put(address, message);
                              publish();
                              queue(address, budget);
                            });
                      });
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  if (valid(address, budget)) {
                    results.put(address, CodecService.error(e));
                    publish();
                    queue(address, budget);
                  }
                });
          }
        });
  }

  private void verifyLast(String address, RetryBudget budget) {
    if (!valid(address, budget)) return;
    ICodecService service = remote;
    worker.execute(
        () -> {
          try {
            JSONObject state = new JSONObject(service.inspect());
            main.post(
                () -> {
                  if (!valid(address, budget) || remote != service) return;
                  snapshot = state;
                  JSONObject d = find(state, address);
                  JSONObject c = d == null ? null : d.optJSONObject("current");
                  boolean matches = c != null && c.optInt("type", -1) == prefs.codec(address);
                  cancel(address);
                  results.put(
                      address,
                      matches
                          ? "Confirmed: " + prefs.codecName(address)
                          : "Saved codec unavailable or rejected. Using the system codec; retry on"
                              + " next connection.");
                  publish();
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  if (valid(address, budget)) {
                    cancel(address);
                    results.put(address, CodecService.error(e));
                    publish();
                  }
                });
          }
        });
  }

  static JSONObject find(JSONObject state, String address) {
    JSONArray devices = state.optJSONArray("devices");
    if (devices != null)
      for (int i = 0; i < devices.length(); i++)
        if (address.equals(devices.optJSONObject(i).optString("address")))
          return devices.optJSONObject(i);
    return null;
  }

  void changed(String address) {
    manualSessions.remove(address);
    cancel(address);
    if (monitoring && prefs.enabled(address)) schedule(address);
    publish();
  }

  void setMonitoring(boolean value) {
    synchronized (prefs) {
      monitoring = value;
    }
    if (!value) cancelAll();
    else refresh(true);
    publish();
  }

  boolean isApplying(String address) {
    return pending.containsKey(address) || manualSessions.containsKey(address);
  }

  boolean needsMonitoring() {
    return (prefs.anyEnabled() && !prefs.store.getBoolean("codecPaused", false))
        || (reconnect != null && reconnect.needsService());
  }

  void pauseCodecs() {
    prefs.store.edit().putBoolean("codecPaused", true).apply();
    setMonitoring(false);
    if (reconnect == null || !reconnect.needsService())
      stopService(new Intent(this, MonitorService.class));
  }

  void setReconnectEnabled(boolean value, Runnable finished) {
    if (reconnectBusy) return;
    reconnectBusy = true;
    reconnectActionMessage = "";
    reconnectActionFailed = false;
    publish();
    worker.execute(
        () -> {
          reconnect.setEnabled(value, remote);
          main.post(
              () -> {
                reconnectBusy = false;
                publish();
                finished.run();
              });
        });
  }

  boolean preventReconnect() {
    return prefs.store.getBoolean("preventReconnect", false);
  }

  void setPreventReconnect(boolean enabled, Runnable finished) {
    if (reconnectBusy) return;
    prefs.store.edit().putBoolean("preventReconnect", enabled).apply();
    if (!enabled) setReconnectEnabled(false, finished);
    else {
      reconnectActionFailed = false;
      reconnectActionMessage = "";
      publish();
      finished.run();
    }
  }

  void turnBluetoothOff(Runnable finished) {
    if (reconnectBusy) return;
    reconnectBusy = true;
    reconnectActionMessage = "";
    reconnectActionFailed = false;
    cancelAll();
    manualSessions.clear();
    publish();
    worker.execute(
        () -> {
          String result;
          boolean failed;
          if (preventReconnect()) {
            result = reconnect.turnBluetoothOff(remote);
            failed = reconnect.operationFailed();
          } else {
            try {
              JSONObject power = new JSONObject(remote.turnBluetoothOff());
              failed = !power.optBoolean("off");
            } catch (Exception e) {
              failed = true;
            }
            result = failed ? "Couldn’t turn Bluetooth off. Try again." : "Bluetooth is off.";
          }
          String message = result;
          boolean unsuccessful = failed;
          main.post(
              () -> {
                reconnectBusy = false;
                reconnectActionMessage = message;
                reconnectActionFailed = unsuccessful;
                refresh(false);
                publish();
                finished.run();
              });
        });
  }

  boolean bluetoothServiceReady() {
    return remote != null && authorized();
  }

  void turnBluetoothOn(Runnable finished) {
    if (reconnectBusy) return;
    reconnectBusy = true;
    reconnectActionMessage = "";
    reconnectActionFailed = false;
    publish();
    worker.execute(
        () -> {
          boolean success = false;
          try {
            success = remote != null && new JSONObject(remote.turnBluetoothOn()).optBoolean("on");
          } catch (Exception ignored) {
          }
          boolean on = success;
          main.post(
              () -> {
                reconnectBusy = false;
                reconnectActionFailed = !on;
                reconnectActionMessage = on ? "" : "Couldn’t turn Bluetooth on. Try again.";
                refresh(false);
                publish();
                finished.run();
              });
        });
  }

  private boolean reconnectReconciling;

  void reconcileReconnect() {
    if (reconnect == null || !reconnect.needsService() || reconnectBusy || reconnectReconciling)
      return;
    reconnectReconciling = true;
    worker.execute(
        () -> {
          reconnect.reconcile(remote);
          main.post(
              () -> {
                reconnectReconciling = false;
                publish();
                if (!needsMonitoring() && serviceRunning)
                  stopService(new Intent(this, MonitorService.class));
              });
        });
  }

  void checkManualConnections() {
    if (checkingManualConnections || reconnectBusy || remote == null) return;
    checkingManualConnections = true;
    ICodecService service = remote;
    boolean enabled = serviceRunning && reconnect.enabled();
    worker.execute(
        () -> {
          JSONObject response;
          try {
            response = new JSONObject(service.manualConnectionRequests(enabled));
          } catch (Exception e) {
            response = new JSONObject();
          }
          JSONArray requests = response.optJSONArray("requests");
          String failure =
              enabled && !response.optBoolean("available")
                  ? "Android device connections unavailable. Open BlueCodec to connect."
                  : "";
          if (enabled && requests != null && service == remote && reconnect.enabled()) {
            for (int i = 0; i < requests.length(); i++) {
              String address = requests.optString(i);
              reconnect.connectFromSettings(service, address);
            }
          }
          main.post(
              () -> {
                checkingManualConnections = false;
                if (!failure.equals(manualConnectionError)) {
                  manualConnectionError = failure;
                  publish();
                }
                if (enabled && requests != null && requests.length() > 0) {
                  main.postDelayed(() -> refresh(false), 1500);
                  main.postDelayed(this::reconcileReconnect, 16000);
                }
              });
        });
  }

  private void rememberLastConnected(JSONObject state) {
    JSONArray devices = state.optJSONArray("devices");
    if (devices == null) return;
    JSONObject last = null;
    for (int i = 0; i < devices.length(); i++) {
      JSONObject d = devices.optJSONObject(i);
      if (d != null
          && d.optBoolean("connected")
          && (last == null || !connected.contains(d.optString("address")))) last = d;
    }
    if (last != null) {
      String stored = prefs.store.getString("lastConnected", "");
      JSONObject old = null;
      try {
        old = new JSONObject(stored);
      } catch (Exception ignored) {
      }
      JSONObject stillConnected = old == null ? null : find(state, old.optString("address"));
      if (stillConnected != null
          && stillConnected.optBoolean("connected")
          && connected.contains(last.optString("address"))) last = stillConnected;
      prefs.store.edit().putString("lastConnected", last.toString()).apply();
    }
  }

  void selectSoundOutput(String target) {
    if (remote == null || !pendingOutput.isEmpty()) return;
    pendingOutput = target;
    outputError = "";
    ICodecService service = remote;
    publish();
    worker.execute(
        () -> {
          String failure = "";
          JSONObject checked = null;
          try {
            JSONObject result = new JSONObject(service.selectSoundOutput(target));
            failure = result.optString("error");
            checked = new JSONObject(service.inspect());
          } catch (Exception e) {
            failure = "Sound output unavailable. Reconnect Shizuku and try again.";
          }
          String error = failure;
          JSONObject state = checked;
          main.post(
              () -> {
                pendingOutput = "";
                if (remote == service) {
                  outputError = error;
                  if (state != null) snapshot = state;
                }
                publish();
              });
        });
  }

  void connectDevice(String address) {
    if (reconnectBusy) return;
    reconnectBusy = true;
    publish();
    worker.execute(
        () -> {
          reconnect.connect(remote, address);
          main.postDelayed(this::reconcileReconnect, 16000);
          main.post(
              () -> {
                reconnectBusy = false;
                publish();
                main.postDelayed(() -> refresh(false), 1500);
              });
        });
  }

  void manual(String address) {
    int type = prefs.codec(address);
    if (remote == null || type < 0 || manualSessions.containsKey(address)) return;
    cancel(address);
    Object token = new Object();
    manualSessions.put(address, token);
    ICodecService service = remote;
    results.put(address, "Applying " + prefs.codecName(address) + "…");
    publish();
    worker.execute(
        () -> {
          String result;
          try {
            result = service.apply(address, type);
          } catch (Exception e) {
            result = CodecService.error(e);
          }
          String message = result;
          main.post(
              () -> {
                if (manualSessions.get(address) != token) return;
                results.put(address, message);
                main.postDelayed(
                    () ->
                        worker.execute(
                            () -> {
                              JSONObject data = null;
                              try {
                                data = new JSONObject(service.inspect());
                              } catch (Exception ignored) {
                              }
                              JSONObject checked = data;
                              main.post(
                                  () -> {
                                    if (manualSessions.get(address) != token) return;
                                    manualSessions.remove(address);
                                    if (service == remote && checked != null) {
                                      snapshot = checked;
                                      JSONObject d = find(checked, address);
                                      JSONObject c = d == null ? null : d.optJSONObject("current");
                                      results.put(
                                          address,
                                          c != null && c.optInt("type", -1) == type
                                              ? "Confirmed: " + prefs.codecName(address)
                                              : "Codec change not confirmed. Using Android’s"
                                                  + " selection.");
                                    }
                                    publish();
                                  });
                            }),
                    1200);
              });
        });
  }
}
