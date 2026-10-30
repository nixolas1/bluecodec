package no.nixo.bluecodec;

import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;

public class MonitorService extends Service {
  static final String CHANNEL = "codec_monitor_quiet";
  private CodecController controller;
  private boolean registered;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable manualConnections =
      new Runnable() {
        public void run() {
          if (!controller.serviceRunning) return;
          controller.checkManualConnections();
          handler.postDelayed(this, 750);
        }
      };
  private final Runnable update =
      () -> {
        if (controller != null)
          getSystemService(NotificationManager.class).notify(1, notification());
      };
  private final BroadcastReceiver receiver =
      new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
          BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
          String action = intent.getAction();
          if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state != BluetoothAdapter.STATE_TURNING_OFF) {
              controller.refresh(false);
              if (state == BluetoothAdapter.STATE_ON) {
                controller.reconnect.bluetoothStarted();
                controller.reconcileReconnect();
              }
            }
            return;
          }
          if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
            controller.refresh(false);
            controller.reconcileReconnect();
            return;
          }
          if (BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
            if (state == BluetoothProfile.STATE_CONNECTED
                || state == BluetoothProfile.STATE_DISCONNECTED) {
              controller.refresh(false);
              controller.reconcileReconnect();
            }
            return;
          }
          if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
            if (device != null
                && (state == BluetoothProfile.STATE_DISCONNECTED
                    || state == BluetoothProfile.STATE_DISCONNECTING)) {
              controller.disconnected(device.getAddress());
              if (state == BluetoothProfile.STATE_DISCONNECTED) controller.reconcileReconnect();
            }
            if (state == BluetoothProfile.STATE_CONNECTED && device != null) {
              controller.connectedEvent(device.getAddress());
              controller.reconcileReconnect();
            }
          } else controller.refresh(false);
        }
      };

  static void channel(Context c) {
    NotificationManager manager = c.getSystemService(NotificationManager.class);
    NotificationChannel channel =
        new NotificationChannel(
            CHANNEL, "Background monitoring", NotificationManager.IMPORTANCE_LOW);
    channel.setShowBadge(false);
    manager.createNotificationChannel(channel);
    // Badge settings are immutable after creation, so migrate the original channel once.
    manager.deleteNotificationChannel("codec_monitor");
  }

  static PendingIntent open(Context c) {
    return PendingIntent.getActivity(
        c,
        0,
        new Intent(c, MainActivity.class),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  private Notification notification() {
    int connectedCount = controller.connectedCount();
    String status = connectedCount == 0 ? "Waiting" : connectedCount + " connected";
    Bundle extras = new Bundle();
    // Notification.EXTRA_PREFER_SMALL_ICON (API 37); use its key while compiling with SDK 36.
    extras.putBoolean("android.app.preferSmallIcon", true);
    Notification.Builder builder =
        new Notification.Builder(this, CHANNEL)
            .addExtras(extras)
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setColor(Notification.COLOR_DEFAULT)
            .setContentTitle("BlueCodec · " + status)
            .setContentText("Press to open")
            .setContentIntent(open(this))
            .setOngoing(true);
    if (controller.monitoring && controller.prefs.anyEnabled())
      builder.addAction(
          new Notification.Action.Builder(
                  null,
                  "Pause codecs",
                  PendingIntent.getService(
                      this,
                      1,
                      new Intent(this, MonitorService.class).setAction("pause"),
                      PendingIntent.FLAG_IMMUTABLE))
              .build());
    return builder.build();
  }

  public void onCreate() {
    super.onCreate();
    controller = (CodecController) getApplication();
    channel(this);
  }

  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && "pause".equals(intent.getAction())) {
      controller.pauseCodecs();
      return START_STICKY;
    }
    if ((!controller.needsMonitoring()
            && (intent == null || !"tile_toggle".equals(intent.getAction())))
        || checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
      stopSelf();
      return START_NOT_STICKY;
    }
    startForeground(1, notification());
    getSystemService(NotificationManager.class).cancel(2);
    if (!registered) {
      IntentFilter filter = new IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
      filter.addAction("android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED");
      filter.addAction("android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED");
      filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
      filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
      filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
      if (Build.VERSION.SDK_INT >= 33)
        registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
      else registerReceiver(receiver, filter);
      registered = true;
      controller.listeners.add(update);
    }
    controller.serviceRunning = true;
    handler.removeCallbacks(manualConnections);
    handler.post(manualConnections);
    controller.setMonitoring(!controller.prefs.store.getBoolean("codecPaused", false));
    controller.reconcileReconnect();
    controller.connect();
    return START_STICKY;
  }

  public void onDestroy() {
    if (registered) unregisterReceiver(receiver);
    controller.listeners.remove(update);
    controller.serviceRunning = false;
    handler.removeCallbacks(manualConnections);
    controller.checkManualConnections();
    controller.setMonitoring(false);
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }

  public IBinder onBind(Intent intent) {
    return null;
  }
}
