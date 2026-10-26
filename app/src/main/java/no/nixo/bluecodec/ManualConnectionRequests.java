package no.nixo.bluecodec;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/** Compatibility bridge for explicit Settings requests; never stores log contents. */
@android.annotation.SuppressLint("MissingPermission")
final class ManualConnectionRequests implements AutoCloseable {
  private static final long MAX_AGE_MS = 5000;
  private static final int MAX_QUEUE = 32;
  private static final Pattern LINE =
      Pattern.compile(
          "^\\s*(\\d+\\.\\d+)\\s+(\\d+)\\s+(\\d+)\\s+\\d+\\s+I\\s+BluetoothAdapterServiceBinder:\\s+connectAllEnabledProfiles:"
              + " device=([0-9A-FX:]+), from uid/pid=(\\d+)/(\\d+)\\s*$");
  private final BluetoothAdapter adapter;
  private final Map<String, Integer> producers = new HashMap<>(), callers = new HashMap<>();
  private final ArrayDeque<Request> pending = new ArrayDeque<>();
  private java.lang.Process process;
  private Thread reader;
  private long started;
  private long generation;
  private boolean enabled, closed, available;
  private String error;

  ManualConnectionRequests(Context context, BluetoothAdapter adapter) {
    this.adapter = adapter;
    trust(context, producers, "com.android.bluetooth");
    trust(context, producers, "com.google.android.bluetooth");
    trust(context, callers, "com.android.settings");
    trust(context, callers, "com.android.systemui");
  }

  private static void trust(Context context, Map<String, Integer> destination, String name) {
    try {
      ApplicationInfo info = context.getPackageManager().getApplicationInfo(name, 0);
      if ((info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
          != 0) destination.put(name, info.uid);
    } catch (Exception ignored) {
    }
  }

  synchronized String drain(boolean requested) {
    if (!requested) stop();
    else if (!closed && !enabled) start();
    JSONArray requests = new JSONArray();
    long now = System.currentTimeMillis();
    Set<String> unique = new LinkedHashSet<>();
    while (!pending.isEmpty()) {
      Request next = pending.removeFirst();
      if (fresh(next.time, started, now)) unique.add(next.address);
    }
    for (String address : unique) requests.put(address);
    JSONObject result = new JSONObject();
    try {
      result.put("available", available && requested).put("requests", requests);
      if (error != null && requested) result.put("error", error);
    } catch (JSONException ignored) {
    }
    return result.toString();
  }

  private void start() {
    enabled = true;
    error = null;
    started = System.currentTimeMillis();
    long token = ++generation;
    if (producers.isEmpty() || callers.isEmpty()) {
      error = "Android connection request monitoring is unavailable.";
      return;
    }
    try {
      String since =
          new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(started));
      process =
          new ProcessBuilder(
                  "/system/bin/logcat",
                  "-b",
                  "main",
                  "-v",
                  "threadtime",
                  "-v",
                  "uid",
                  "-v",
                  "epoch",
                  "-T",
                  since,
                  "BluetoothAdapterServiceBinder:I",
                  "*:S")
              .redirectErrorStream(true)
              .start();
      java.lang.Process current = process;
      available = true;
      reader = new Thread(() -> read(current, token), "bluecodec-manual-connect");
      reader.setDaemon(true);
      reader.start();
    } catch (IOException failure) {
      available = false;
      error = "Android connection request monitoring could not start.";
    }
  }

  private void read(java.lang.Process current, long token) {
    try (BufferedReader input =
        new BufferedReader(
            new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = input.readLine()) != null) {
        Event event = parse(line);
        if (event == null) continue;
        long now = System.currentTimeMillis();
        synchronized (this) {
          if (token != generation || !enabled) return;
          if (!fresh(event.time, started, now)) continue;
        }
        if (!identity(event.producerPid, event.producerUid, producers)
            || !identity(event.callerPid, event.callerUid, callers)) continue;
        List<String> bonded = new ArrayList<>();
        for (BluetoothDevice device : adapter.getBondedDevices()) bonded.add(device.getAddress());
        String address = resolve(event.device, bonded);
        if (address == null) continue;
        synchronized (this) {
          if (token != generation
              || !enabled
              || !fresh(event.time, started, System.currentTimeMillis())) continue;
          if (pending.size() == MAX_QUEUE) pending.removeFirst();
          pending.addLast(new Request(address, event.time));
        }
      }
    } catch (Exception ignored) {
      // Missing proc identity, permission, or changed firmware fails closed.
    } finally {
      current.destroyForcibly();
      synchronized (this) {
        if (token == generation) {
          available = false;
          pending.clear();
          error = "Android connection request monitoring stopped.";
        }
      }
    }
  }

  private static boolean identity(int pid, int uid, Map<String, Integer> trusted) {
    if (pid <= 0) return false;
    try {
      byte[] raw = Files.readAllBytes(new File("/proc/" + pid + "/cmdline").toPath());
      int end = 0;
      while (end < raw.length && raw[end] != 0) end++;
      String name = new String(raw, 0, end, StandardCharsets.UTF_8);
      if (!Objects.equals(trusted.get(name), uid)) return false;
      for (String line : Files.readAllLines(new File("/proc/" + pid + "/status").toPath())) {
        if (!line.startsWith("Uid:")) continue;
        String[] ids = line.substring(4).trim().split("\\s+");
        if (ids.length != 4) return false;
        for (String id : ids) if (Integer.parseInt(id) != uid) return false;
        return true;
      }
    } catch (Exception ignored) {
    }
    return false;
  }

  static Event parse(String line) {
    Matcher m = LINE.matcher(line);
    if (!m.matches()) return null;
    try {
      return new Event(
          new java.math.BigDecimal(m.group(1)).movePointRight(3).longValueExact(),
          Integer.parseInt(m.group(2)),
          Integer.parseInt(m.group(3)),
          m.group(4),
          Integer.parseInt(m.group(5)),
          Integer.parseInt(m.group(6)));
    } catch (RuntimeException invalid) {
      return null;
    }
  }

  static boolean fresh(long timestamp, long start, long now) {
    return timestamp >= start && timestamp <= now && now - timestamp <= MAX_AGE_MS;
  }

  static String resolve(String logged, Collection<String> bonded) {
    boolean full = logged.matches("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}");
    boolean redacted = logged.matches("XX:XX:XX:XX:[0-9A-F]{2}:[0-9A-F]{2}");
    if (!full && !redacted) return null;
    String match = null;
    for (String address : bonded) {
      boolean same =
          full
              ? address.equalsIgnoreCase(logged)
              : address.toUpperCase(Locale.US).endsWith(logged.substring(12));
      if (!same) continue;
      if (match != null && !match.equalsIgnoreCase(address)) return null;
      match = address;
    }
    return match;
  }

  private void stop() {
    enabled = false;
    available = false;
    generation++;
    pending.clear();
    if (process != null) process.destroyForcibly();
    process = null;
    reader = null;
  }

  @Override
  public synchronized void close() {
    closed = true;
    stop();
  }

  static final class Event {
    final long time;
    final int producerUid, producerPid, callerUid, callerPid;
    final String device;

    Event(
        long time, int producerUid, int producerPid, String device, int callerUid, int callerPid) {
      this.time = time;
      this.producerUid = producerUid;
      this.producerPid = producerPid;
      this.device = device;
      this.callerUid = callerUid;
      this.callerPid = callerPid;
    }
  }

  private static final class Request {
    final String address;
    final long time;

    Request(String address, long time) {
      this.address = address;
      this.time = time;
    }
  }
}
