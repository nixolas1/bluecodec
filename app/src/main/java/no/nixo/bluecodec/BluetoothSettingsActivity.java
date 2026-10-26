package no.nixo.bluecodec;

import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import android.provider.Settings;

/** Long press from SystemUI opens the actual Android device list. */
public class BluetoothSettingsActivity extends Activity {
  public void onCreate(Bundle state) {
    super.onCreate(state);
    CodecController controller = (CodecController) getApplication();
    if (controller.needsMonitoring()) {
      try {
        startForegroundService(new Intent(this, MonitorService.class));
      } catch (RuntimeException e) {
        android.widget.Toast.makeText(
                this, "Open BlueCodec to connect your headset.", android.widget.Toast.LENGTH_LONG)
            .show();
      }
    }
    try {
      startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
    } catch (ActivityNotFoundException e) {
      startActivity(new Intent(Settings.ACTION_SETTINGS));
    }
    finish();
  }
}
