package no.nixo.bluecodec;

import android.Manifest;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.*;
import android.provider.Settings;
import android.service.quicksettings.*;
import android.widget.Toast;

/** SystemUI owns tile size/layout; the same label and subtitle serve both sizes. */
public class BluetoothTileService extends TileService {
  private final Handler handler = new Handler(Looper.getMainLooper());
  private CodecController controller;
  private boolean listening;
  private final Runnable update = this::render;
  private final Runnable poll =
      new Runnable() {
        public void run() {
          if (!listening) return;
          controller.refresh(false);
          render();
          handler.postDelayed(this, 3000);
        }
      };
  private final BroadcastReceiver changes =
      new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
          controller.refresh(false);
          render();
        }
      };

  public void onCreate() {
    super.onCreate();
    controller = (CodecController) getApplication();
  }

  public void onStartListening() {
    super.onStartListening();
    if (listening) return;
    listening = true;
    controller.listeners.add(update);
    IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
    filter.addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED");
    filter.addAction("android.bluetooth.device.action.ACL_CONNECTED");
    filter.addAction("android.bluetooth.device.action.ACL_DISCONNECTED");
    filter.addAction("android.bluetooth.device.action.ALIAS_CHANGED");
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(changes, filter, RECEIVER_EXPORTED);
    else registerReceiver(changes, filter);
    handler.post(poll);
  }

  public void onStopListening() {
    if (listening) unregisterReceiver(changes);
    listening = false;
    controller.listeners.remove(update);
    handler.removeCallbacks(poll);
    super.onStopListening();
  }

  public void onDestroy() {
    onStopListening();
    super.onDestroy();
  }

  private int bluetoothState() {
    if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
        != PackageManager.PERMISSION_GRANTED) return BluetoothAdapter.ERROR;
    try {
      BluetoothAdapter adapter = getSystemService(BluetoothManager.class).getAdapter();
      return adapter == null ? BluetoothAdapter.ERROR : adapter.getState();
    } catch (SecurityException e) {
      return BluetoothAdapter.ERROR;
    }
  }

  private void render() {
    if (!listening || getQsTile() == null) return;
    int state = bluetoothState();
    Tile tile = getQsTile();
    TileDisplay display =
        TileDisplay.from(
            state,
            controller.reconnectBusy,
            controller.bluetoothServiceReady(),
            controller.authorized(),
            controller.snapshot);
    tile.setLabel(display.label);
    tile.setSubtitle(display.subtitle);
    tile.setState(display.state);
    tile.setIcon(Icon.createWithResource(this, R.drawable.ic_bluetooth));
    tile.setContentDescription(display.label + ", " + display.subtitle);
    tile.setStateDescription(display.subtitle);
    tile.updateTile();
  }

  public void onClick() {
    super.onClick();
    if (controller.reconnectBusy) return;
    if (isLocked()) unlockAndRun(this::toggle);
    else toggle();
  }

  @android.annotation.SuppressLint(
      "StartActivityAndCollapseDeprecated") // Intent overload only on API 31–33.
  private void toggle() {
    int state = bluetoothState();
    if (controller.reconnectBusy
        || state == BluetoothAdapter.STATE_TURNING_ON
        || state == BluetoothAdapter.STATE_TURNING_OFF) return;
    if (!controller.bluetoothServiceReady() || state == BluetoothAdapter.ERROR) {
      Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      if (Build.VERSION.SDK_INT >= 34)
        startActivityAndCollapse(
            PendingIntent.getActivity(
                this, 20, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
      else startActivityAndCollapse(open);
      return;
    }
    // Start while the tile interaction grants foreground-service eligibility, not
    // several seconds later when the asynchronous Bluetooth operation completes.
    if (state == BluetoothAdapter.STATE_ON || controller.needsMonitoring()) {
      Intent monitor = new Intent(this, MonitorService.class).setAction("tile_toggle");
      try {
        startForegroundService(monitor);
      } catch (RuntimeException e) {
        Toast.makeText(this, "Open BlueCodec to change Bluetooth.", Toast.LENGTH_LONG).show();
        return;
      }
    }
    if (state == BluetoothAdapter.STATE_ON) {
      controller.turnBluetoothOff(this::finished);
    } else if (state == BluetoothAdapter.STATE_OFF) {
      controller.turnBluetoothOn(this::finished);
      // Launch during the tile tap while SystemUI permits collapsing the shade.
      // Settings observes Bluetooth turning on and then shows available devices.
      Intent settings =
          new Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      if (Build.VERSION.SDK_INT >= 34)
        startActivityAndCollapse(
            PendingIntent.getActivity(
                this, 21, settings, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
      else startActivityAndCollapse(settings);
    }
  }

  private void finished() {
    if (controller.reconnectActionFailed)
      Toast.makeText(this, controller.reconnectActionMessage, Toast.LENGTH_LONG).show();
    if (!controller.needsMonitoring()) stopService(new Intent(this, MonitorService.class));
    render();
  }
}
