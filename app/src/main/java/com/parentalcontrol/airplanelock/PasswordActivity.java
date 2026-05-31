package com.parentalcontrol.airplanelock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class PasswordActivity extends AppCompatActivity {

    public static final String EXTRA_TYPE       = "lock_type";
    public static final String TYPE_AIRPLANE    = "airplane";
    public static final String TYPE_MOBILE_DATA = "mobile_data";
    public static final String TYPE_POWER       = "power";

    // Checked by MonitorService to avoid stacking duplicate screens
    static boolean isShowing = false;

    private EditText passwordInput;
    private TextView attemptsText;
    private Button   unlockBtn;
    private CountDownTimer lockoutTimer;
    private int failedAttempts = 0;
    private static final int MAX_ATTEMPTS = 5;
    private boolean isLockedOut = false;

    private String lockType;
    private BroadcastReceiver airplaneModeOffReceiver;
    private ContentObserver   mobileDataObserver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Only TURN_SCREEN_ON + SHOW_WHEN_LOCKED — no FLAG_KEEP_SCREEN_ON (that wastes battery)
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        setContentView(R.layout.activity_password);
        isShowing = true;

        lockType      = getIntent().getStringExtra(EXTRA_TYPE);
        if (lockType == null) lockType = TYPE_AIRPLANE;

        passwordInput   = findViewById(R.id.passwordInput);
        TextView msgText = findViewById(R.id.messageText);
        attemptsText    = findViewById(R.id.attemptsText);
        unlockBtn       = findViewById(R.id.unlockBtn);
        Button actionBtn = findViewById(R.id.turnOffAirplaneBtn);
        Button bypassBtn = findViewById(R.id.bypassBtn);

        SharedPreferences prefs = getSharedPreferences("AirplaneLockPrefs", MODE_PRIVATE);
        String storedHash = prefs.getString("hashed_password", "");
        String bypassHash = prefs.getString("bypass_password", "");

        // If bypass mode is active, dismiss immediately
        if (prefs.getBoolean("bypass_active", false)) {
            finish();
            return;
        }

        if (TextUtils.isEmpty(storedHash)) {
            msgText.setText("No parental password set.\nPlease open the app and set a password.");
            unlockBtn.setText("Close");
            unlockBtn.setOnClickListener(v -> finish());
            if (actionBtn != null) actionBtn.setVisibility(View.GONE);
            if (bypassBtn != null) bypassBtn.setVisibility(View.GONE);
            return;
        }

        switch (lockType) {
            case TYPE_MOBILE_DATA:
                msgText.setText("Mobile data was turned OFF.\nEnter the parental password to allow this,\nor turn mobile data back ON.");
                actionBtn.setText("Turn Mobile Data Back ON");
                actionBtn.setOnClickListener(v -> openSettings(Settings.ACTION_DATA_ROAMING_SETTINGS));
                break;
            case TYPE_POWER:
                msgText.setText("Power-off / restart is blocked.\nEnter the parental password to allow it.");
                actionBtn.setVisibility(View.GONE);
                break;
            default:
                msgText.setText("Airplane mode was turned ON.\nEnter the parental password to allow this,\nor turn airplane mode back OFF.");
                actionBtn.setText("Turn Off Airplane Mode");
                actionBtn.setOnClickListener(v -> openSettings(Settings.ACTION_AIRPLANE_MODE_SETTINGS));
                break;
        }

        unlockBtn.setOnClickListener(v -> {
            if (isLockedOut) {
                Toast.makeText(this, "Too many attempts. Please wait.", Toast.LENGTH_SHORT).show();
                return;
            }
            String entered = passwordInput.getText().toString();
            if (TextUtils.isEmpty(entered)) {
                Toast.makeText(this, "Please enter the password", Toast.LENGTH_SHORT).show();
                return;
            }
            if (MainActivity.hash(entered).equals(storedHash)) {
                AccessLog.record(this, AccessLog.EVENT_UNLOCK_SUCCESS);
                finish();
            } else {
                failedAttempts++;
                passwordInput.setText("");
                AccessLog.record(this, AccessLog.EVENT_UNLOCK_FAILED);
                if (failedAttempts >= MAX_ATTEMPTS) {
                    lockOutUser();
                } else {
                    attemptsText.setText("Wrong password! " + (MAX_ATTEMPTS - failedAttempts) + " attempt(s) left.");
                    attemptsText.setTextColor(getColor(android.R.color.holo_red_dark));
                }
            }
        });

        if (bypassBtn != null && !TextUtils.isEmpty(bypassHash)) {
            bypassBtn.setVisibility(View.VISIBLE);
            bypassBtn.setOnClickListener(v -> showBypassDialog(prefs, bypassHash));
        }

        registerAutoDissmissWatchers();
    }

    private void showBypassDialog(SharedPreferences prefs, String bypassHash) {
        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Enter bypass code");
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Travel / Bypass Mode")
                .setMessage("Enter your bypass code to temporarily disable all protection.")
                .setView(input)
                .setPositiveButton("Activate Bypass", (d, w) -> {
                    String entered = input.getText().toString();
                    if (MainActivity.hash(entered).equals(bypassHash)) {
                        prefs.edit().putBoolean("bypass_active", true).apply();
                        AccessLog.record(this, AccessLog.EVENT_BYPASS_ON);
                        Toast.makeText(this,
                                "Bypass mode ON. Restrictions disabled until turned off in app.",
                                Toast.LENGTH_LONG).show();
                        finish();
                    } else {
                        Toast.makeText(this, "Wrong bypass code.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openSettings(String action) {
        Intent intent = new Intent(action);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void registerAutoDissmissWatchers() {
        if (TYPE_AIRPLANE.equals(lockType)) {
            airplaneModeOffReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    boolean isOn = Settings.Global.getInt(
                            getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
                    if (!isOn) finish();
                }
            };
            registerReceiver(airplaneModeOffReceiver,
                    new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED));

        } else if (TYPE_MOBILE_DATA.equals(lockType)) {
            Uri uri = Settings.Global.getUriFor(Settings.Global.MOBILE_DATA);
            mobileDataObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    int enabled = Settings.Global.getInt(
                            getContentResolver(), Settings.Global.MOBILE_DATA, 1);
                    if (enabled == 1) finish();
                }
            };
            getContentResolver().registerContentObserver(uri, false, mobileDataObserver);
        }
    }

    private void lockOutUser() {
        isLockedOut = true;
        AccessLog.record(this, AccessLog.EVENT_LOCKOUT);
        unlockBtn.setEnabled(false);
        passwordInput.setEnabled(false);
        // Store reference so we can cancel if activity is destroyed early
        lockoutTimer = new CountDownTimer(60000, 1000) {
            public void onTick(long ms) {
                attemptsText.setText("Too many wrong attempts. Try again in " + (ms / 1000) + "s");
                attemptsText.setTextColor(getColor(android.R.color.holo_red_dark));
            }
            public void onFinish() {
                isLockedOut = false;
                failedAttempts = 0;
                if (!isFinishing()) {
                    unlockBtn.setEnabled(true);
                    passwordInput.setEnabled(true);
                    attemptsText.setText("");
                }
            }
        }.start();
    }

    @Override
    public void onBackPressed() {
        if (TYPE_POWER.equals(lockType)) {
            finish(); // back = cancel the power action
        } else {
            Toast.makeText(this, "Enter the parental password to continue", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_APP_SWITCH)
            return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Do NOT re-launch self here — that caused activity stack buildup.
        // MonitorService will re-trigger the screen if the setting is still blocked.
    }

    @Override
    protected void onDestroy() {
        isShowing = false;
        // Cancel timer to avoid it running against a dead activity
        if (lockoutTimer != null) {
            lockoutTimer.cancel();
            lockoutTimer = null;
        }
        if (airplaneModeOffReceiver != null) {
            try { unregisterReceiver(airplaneModeOffReceiver); } catch (Exception ignored) {}
        }
        if (mobileDataObserver != null) {
            try { getContentResolver().unregisterContentObserver(mobileDataObserver); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
