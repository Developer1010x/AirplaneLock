package com.parentalcontrol.airplanelock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

public class AirplaneModeReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            // Restart the monitor service after device reboot
            Intent serviceIntent = new Intent(context, MonitorService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            return;
        }

        if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(action)) {
            boolean isAirplaneModeOn = Settings.Global.getInt(
                    context.getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 0) != 0;

            if (isAirplaneModeOn) {
                // Airplane mode was just turned ON — launch password screen
                Intent lockIntent = new Intent(context, PasswordActivity.class);
                lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                context.startActivity(lockIntent);
            }
        }
    }
}
