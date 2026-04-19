package com.parentalcontrol.airplanelock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import androidx.core.app.NotificationCompat;

public class MonitorService extends Service {

    private static final String TAG = "AirplaneLock";
    private static final String CHANNEL_ID = "airplane_lock_channel";
    private static final int NOTIFICATION_ID = 1001;

    // Debounce: ignore repeated events within this window (ms)
    private static final long DEBOUNCE_MS = 1000;

    // Single background thread for ALL observers — zero main-thread overhead
    private HandlerThread observerThread;
    private Handler observerHandler;

    private BroadcastReceiver airplaneModeReceiver;
    private ContentObserver mobileDataObserver;
    private ContentObserver batterySaverObserver;

    // Timestamps for debouncing each observer
    private long lastMobileDataTrigger = 0;
    private long lastBatterySaverTrigger = 0;

    @Override
    public void onCreate() {
        super.onCreate();

        // Start a single background thread shared by all observers
        observerThread = new HandlerThread("AirplaneLockObserver",
                android.os.Process.THREAD_PRIORITY_BACKGROUND);
        observerThread.start();
        observerHandler = new Handler(observerThread.getLooper());

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        registerAirplaneModeReceiver();
        registerMobileDataObserver();
        registerBatterySaverObserver();
        enableBatterySaver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY already handles restarts — no manual restart needed
        enableBatterySaver();
        return START_STICKY;
    }

    // ── Airplane mode ──────────────────────────────────────────────────────────

    private void registerAirplaneModeReceiver() {
        airplaneModeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean isOn = Settings.Global.getInt(
                        getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
                if (isOn) launchPasswordScreen(PasswordActivity.TYPE_AIRPLANE);
            }
        };
        registerReceiver(airplaneModeReceiver,
                new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED));
    }

    // ── Mobile data ────────────────────────────────────────────────────────────

    private void registerMobileDataObserver() {
        Uri uri = Settings.Global.getUriFor(Settings.Global.MOBILE_DATA);
        // observerHandler runs on background thread — never blocks main thread
        mobileDataObserver = new ContentObserver(observerHandler) {
            @Override
            public void onChange(boolean selfChange) {
                long now = System.currentTimeMillis();
                if (now - lastMobileDataTrigger < DEBOUNCE_MS) return; // debounce
                lastMobileDataTrigger = now;

                int enabled = Settings.Global.getInt(
                        getContentResolver(), Settings.Global.MOBILE_DATA, 1);
                if (enabled == 0) launchPasswordScreen(PasswordActivity.TYPE_MOBILE_DATA);
            }
        };
        getContentResolver().registerContentObserver(uri, false, mobileDataObserver);
    }

    // ── Battery Saver ──────────────────────────────────────────────────────────

    private void registerBatterySaverObserver() {
        Uri uri = Settings.Global.getUriFor(Settings.Global.LOW_POWER_MODE);
        batterySaverObserver = new ContentObserver(observerHandler) {
            @Override
            public void onChange(boolean selfChange) {
                long now = System.currentTimeMillis();
                if (now - lastBatterySaverTrigger < DEBOUNCE_MS) return; // debounce
                lastBatterySaverTrigger = now;

                int isOn = Settings.Global.getInt(
                        getContentResolver(), Settings.Global.LOW_POWER_MODE, 1);
                if (isOn == 0) enableBatterySaver(); // silently re-enable
            }
        };
        getContentResolver().registerContentObserver(uri, false, batterySaverObserver);
    }

    static void enableBatterySaver(Context context) {
        try {
            Settings.Global.putInt(context.getContentResolver(),
                    Settings.Global.LOW_POWER_MODE, 1);
        } catch (SecurityException e) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted — run: " +
                    "adb shell pm grant com.parentalcontrol.airplanelock " +
                    "android.permission.WRITE_SECURE_SETTINGS");
        }
    }

    private void enableBatterySaver() {
        enableBatterySaver(this);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void launchPasswordScreen(String type) {
        if (PasswordActivity.isShowing) return; // don't stack duplicate screens
        Intent intent = new Intent(this, PasswordActivity.class);
        intent.putExtra(PasswordActivity.EXTRA_TYPE, type);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private Notification buildNotification() {
        Intent openAppIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Parental Control Active")
                .setContentText("Monitoring airplane mode, mobile data & battery saver.")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN) // lowest priority = least battery
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Parental Control",
                    NotificationManager.IMPORTANCE_MIN); // silent, no vibration
            channel.setDescription("Keeps parental controls running at all times");
            channel.setShowBadge(false);
            NotificationManager mgr = getSystemService(NotificationManager.class);
            if (mgr != null) mgr.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (airplaneModeReceiver != null) {
            try { unregisterReceiver(airplaneModeReceiver); } catch (Exception ignored) {}
        }
        if (mobileDataObserver != null) {
            try { getContentResolver().unregisterContentObserver(mobileDataObserver); } catch (Exception ignored) {}
        }
        if (batterySaverObserver != null) {
            try { getContentResolver().unregisterContentObserver(batterySaverObserver); } catch (Exception ignored) {}
        }
        if (observerThread != null) {
            observerThread.quitSafely();
        }
        // No manual restart here — START_STICKY handles it cleanly without a restart loop
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
