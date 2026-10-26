package no.nixo.bluecodec;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * A reboot requires Shizuku restoration; never claim automation is active just from saved
 * preferences.
 */
public class BootReceiver extends BroadcastReceiver {
  public void onReceive(Context context, Intent intent) {
    if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
        && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
    if (!new Preferences(context).anyEnabled() && !new ReconnectSettings(context).needsService())
      return;
    if (Build.VERSION.SDK_INT >= 33
        && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return;
    MonitorService.channel(context);
    context
        .getSystemService(NotificationManager.class)
        .notify(
            2,
            new Notification.Builder(context, MonitorService.CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Resume BlueCodec")
                .setContentText(
                    "Open BlueCodec, restore Shizuku if needed, then resume monitoring.")
                .setContentIntent(MonitorService.open(context))
                .setAutoCancel(true)
                .build());
  }
}
