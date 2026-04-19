package com.parentalcontrol.airplanelock;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * Device Admin receiver.
 * When active, the app cannot be uninstalled from Settings without
 * first disabling Device Admin — which we protect with a password.
 */
public class AdminReceiver extends DeviceAdminReceiver {

    @Override
    public void onEnabled(Context context, Intent intent) {
        Toast.makeText(context,
                "Parental Control: Device Admin enabled. App is now protected.",
                Toast.LENGTH_LONG).show();
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        return "Enter the parental password in the app before disabling Device Admin.";
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Toast.makeText(context,
                "Parental Control: Device Admin disabled.",
                Toast.LENGTH_LONG).show();
    }
}
