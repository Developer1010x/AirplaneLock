package com.parentalcontrol.airplanelock;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_DEVICE_ADMIN = 101;
    private static final int REQUEST_OVERLAY      = 102;

    private SharedPreferences prefs;
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;

    private TextView statusText;
    private TextView protectionStatus;
    private TextView bypassStatusText;
    private TextView batterySaverStatus;
    private Button   toggleBypassBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs          = getSharedPreferences("AirplaneLockPrefs", MODE_PRIVATE);
        dpm            = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, AdminReceiver.class);

        // Pre-load hard-coded bypass code on first launch
        if (TextUtils.isEmpty(prefs.getString("bypass_password", ""))) {
            prefs.edit().putString("bypass_password", hash("TRAVEL2024")).apply();
        }

        bindViews();
        refreshStatus();
        wireButtons();
        requestOverlayPermission();
        startMonitorService();
        MonitorService.enableBatterySaver(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void bindViews() {
        statusText         = findViewById(R.id.statusText);
        protectionStatus   = findViewById(R.id.protectionStatus);
        bypassStatusText   = findViewById(R.id.bypassStatusText);
        batterySaverStatus = findViewById(R.id.batterySaverStatus);
        toggleBypassBtn    = findViewById(R.id.toggleBypassBtn);
    }

    private void refreshStatus() {
        boolean hasPassword   = !TextUtils.isEmpty(prefs.getString("hashed_password", ""));
        boolean isAdminActive = dpm.isAdminActive(adminComponent);
        boolean bypassActive  = prefs.getBoolean("bypass_active", false);
        boolean hasBypass     = !TextUtils.isEmpty(prefs.getString("bypass_password", ""));

        if (hasPassword && isAdminActive) {
            statusText.setText("Protection: ACTIVE");
            statusText.setTextColor(getColor(android.R.color.holo_green_dark));
        } else if (hasPassword) {
            statusText.setText("Protection: Partial (enable Device Admin for full protection)");
            statusText.setTextColor(getColor(android.R.color.holo_orange_dark));
        } else {
            statusText.setText("Protection: NOT SET — Please set a password below");
            statusText.setTextColor(getColor(android.R.color.holo_red_dark));
        }
        protectionStatus.setText(isAdminActive
                ? "Device Admin: Enabled"
                : "Device Admin: Disabled (tap button below to enable)");

        int bsOn = Settings.Global.getInt(getContentResolver(), Settings.Global.LOW_POWER_MODE, 0);
        batterySaverStatus.setText(bsOn == 1
                ? "Status: ON — battery saver is active"
                : "Status: OFF — run the ADB command below to enable");
        batterySaverStatus.setTextColor(bsOn == 1
                ? getColor(android.R.color.holo_green_dark)
                : getColor(android.R.color.holo_orange_dark));

        if (bypassActive) {
            bypassStatusText.setText("Bypass Mode: ON — all restrictions currently disabled");
            bypassStatusText.setTextColor(getColor(android.R.color.holo_orange_dark));
            toggleBypassBtn.setText("Turn Bypass Mode OFF");
            toggleBypassBtn.getBackground().setTint(0xFFC62828);
        } else {
            bypassStatusText.setText("Bypass Mode: OFF — restrictions active");
            bypassStatusText.setTextColor(getColor(android.R.color.holo_green_dark));
            toggleBypassBtn.setText("Turn Bypass Mode ON");
            toggleBypassBtn.getBackground().setTint(0xFF00796B);
        }
        toggleBypassBtn.setEnabled(hasBypass);
    }

    private void wireButtons() {
        EditText currentPasswordInput = findViewById(R.id.currentPasswordInput);
        EditText newPasswordInput     = findViewById(R.id.newPasswordInput);
        EditText confirmPasswordInput = findViewById(R.id.confirmPasswordInput);
        Button   savePasswordBtn      = findViewById(R.id.savePasswordBtn);
        Button   activateAdminBtn     = findViewById(R.id.activateAdminBtn);
        EditText bypassCodeInput      = findViewById(R.id.bypassCodeInput);
        Button   saveBypassBtn        = findViewById(R.id.saveBypassBtn);

        savePasswordBtn.setOnClickListener(v -> {
            String currentPass = currentPasswordInput.getText().toString();
            String newPass     = newPasswordInput.getText().toString();
            String confirmPass = confirmPasswordInput.getText().toString();
            String storedHash  = prefs.getString("hashed_password", "");

            if (!TextUtils.isEmpty(storedHash) && !hash(currentPass).equals(storedHash)) {
                Toast.makeText(this, "Current password is incorrect", Toast.LENGTH_SHORT).show();
                return;
            }
            if (newPass.length() < 4) {
                Toast.makeText(this, "Password must be at least 4 characters", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!newPass.equals(confirmPass)) {
                Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.edit().putString("hashed_password", hash(newPass)).apply();
            currentPasswordInput.setText("");
            newPasswordInput.setText("");
            confirmPasswordInput.setText("");
            Toast.makeText(this, "Password saved!", Toast.LENGTH_SHORT).show();
            refreshStatus();
        });

        saveBypassBtn.setOnClickListener(v -> {
            String code = bypassCodeInput.getText().toString();
            if (TextUtils.isEmpty(prefs.getString("hashed_password", ""))) {
                Toast.makeText(this, "Set a main password first", Toast.LENGTH_SHORT).show();
                return;
            }
            if (code.length() < 2) {
                Toast.makeText(this, "Bypass code must be at least 2 characters", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.edit().putString("bypass_password", hash(code)).apply();
            bypassCodeInput.setText("");
            Toast.makeText(this, "Bypass code saved!", Toast.LENGTH_SHORT).show();
            refreshStatus();
        });

        toggleBypassBtn.setOnClickListener(v -> {
            if (prefs.getBoolean("bypass_active", false)) {
                showPasswordConfirmDialog(() -> {
                    prefs.edit().putBoolean("bypass_active", false).apply();
                    AccessLog.record(this, AccessLog.EVENT_BYPASS_OFF);
                    Toast.makeText(this, "Bypass mode OFF. Restrictions active.", Toast.LENGTH_SHORT).show();
                    refreshStatus();
                });
            } else {
                showBypassCodeDialog();
            }
        });

        Button viewLogBtn = findViewById(R.id.viewLogBtn);
        if (viewLogBtn != null) {
            viewLogBtn.setOnClickListener(v -> showActivityLogDialog());
        }

        activateAdminBtn.setOnClickListener(v -> {
            if (!dpm.isAdminActive(adminComponent)) {
                Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
                intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Prevents your child from uninstalling this parental control app.");
                startActivityForResult(intent, REQUEST_DEVICE_ADMIN);
            } else {
                Toast.makeText(this, "Device Admin is already active", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showPasswordConfirmDialog(Runnable onSuccess) {
        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Enter main parental password");
        new AlertDialog.Builder(this)
                .setTitle("Confirm")
                .setView(input)
                .setPositiveButton("Confirm", (d, w) -> {
                    if (hash(input.getText().toString())
                            .equals(prefs.getString("hashed_password", ""))) {
                        onSuccess.run();
                    } else {
                        Toast.makeText(this, "Wrong password", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showBypassCodeDialog() {
        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Enter bypass code");
        new AlertDialog.Builder(this)
                .setTitle("Activate Travel / Bypass Mode")
                .setMessage("Enter your bypass code to temporarily disable all restrictions.")
                .setView(input)
                .setPositiveButton("Activate", (d, w) -> {
                    if (hash(input.getText().toString())
                            .equals(prefs.getString("bypass_password", ""))) {
                        prefs.edit().putBoolean("bypass_active", true).apply();
                        Toast.makeText(this, "Bypass mode ON. All restrictions disabled.",
                                Toast.LENGTH_LONG).show();
                        refreshStatus();
                    } else {
                        Toast.makeText(this, "Wrong bypass code", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Shows the tamper / activity log so a parent can review every blocked
     * airplane-mode, mobile-data and power-off attempt, plus failed unlocks and
     * bypass activations. Offers a one-tap option to clear the history.
     */
    private void showActivityLogDialog() {
        java.util.List<String> entries = AccessLog.getFormattedEntries(this);
        CharSequence body;
        if (entries.isEmpty()) {
            body = "No activity recorded yet.\n\nBlocked airplane-mode, mobile-data and "
                    + "power-off attempts will appear here.";
        } else {
            body = TextUtils.join("\n\n", entries);
        }

        TextView view = new TextView(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        view.setPadding(pad, pad, pad, pad);
        view.setTextSize(13);
        view.setText(body);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(view);

        new AlertDialog.Builder(this)
                .setTitle("Activity Log (" + entries.size() + ")")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .setNegativeButton("Clear Log", (d, w) -> {
                    AccessLog.clear(this);
                    Toast.makeText(this, "Activity log cleared", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Permission Required")
                    .setMessage("This app needs 'Display over other apps' permission to show the password screen.")
                    .setPositiveButton("Grant Permission", (d, w) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivityForResult(intent, REQUEST_OVERLAY);
                    })
                    .setCancelable(false)
                    .show();
        }
    }

    private void startMonitorService() {
        Intent serviceIntent = new Intent(this, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_DEVICE_ADMIN || requestCode == REQUEST_OVERLAY) {
            refreshStatus();
        }
    }

    public static String hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return input;
        }
    }
}
