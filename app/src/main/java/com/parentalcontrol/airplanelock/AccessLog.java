package com.parentalcontrol.airplanelock;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Lightweight tamper / activity log.
 *
 * Records security-relevant events (airplane mode triggers, mobile-data toggles,
 * blocked power-off attempts, failed unlock attempts and bypass activations) so a
 * parent can see what their child attempted while the device was monitored.
 *
 * The log is stored as a small JSON array in the same SharedPreferences file the
 * rest of the app uses, capped at {@link #MAX_ENTRIES} so it never grows unbounded
 * or impacts battery/storage. All methods are static and self-contained — nothing
 * in the existing flow needs to change beyond a single {@code AccessLog.record(...)}
 * call at each event site.
 */
public final class AccessLog {

    private static final String PREFS_NAME = "AirplaneLockPrefs";
    private static final String KEY_LOG    = "access_log";

    /** Keep only the most recent N events. */
    public static final int MAX_ENTRIES = 100;

    // Event type constants (kept short to minimise stored size)
    public static final String EVENT_AIRPLANE_BLOCKED   = "Airplane mode turned ON";
    public static final String EVENT_MOBILE_DATA_BLOCKED = "Mobile data turned OFF";
    public static final String EVENT_POWER_BLOCKED      = "Power-off / restart attempted";
    public static final String EVENT_UNLOCK_SUCCESS     = "Unlocked with parental password";
    public static final String EVENT_UNLOCK_FAILED      = "Wrong password entered";
    public static final String EVENT_LOCKOUT            = "Locked out (too many attempts)";
    public static final String EVENT_BYPASS_ON          = "Travel / bypass mode activated";
    public static final String EVENT_BYPASS_OFF         = "Travel / bypass mode turned off";

    private static final SimpleDateFormat FORMAT =
            new SimpleDateFormat("MMM d, yyyy  HH:mm:ss", Locale.getDefault());

    private AccessLog() { /* no instances */ }

    /**
     * Append an event to the log. Safe to call from any thread; the underlying
     * SharedPreferences write is committed asynchronously via {@code apply()}.
     *
     * @param context any valid context
     * @param event   one of the EVENT_* constants (or any short description)
     */
    public static synchronized void record(Context context, String event) {
        if (context == null || TextUtils.isEmpty(event)) return;

        SharedPreferences prefs =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        JSONArray array = readArray(prefs);

        try {
            JSONObject entry = new JSONObject();
            entry.put("t", System.currentTimeMillis());
            entry.put("e", event);
            array.put(entry);
        } catch (JSONException ignored) {
            return;
        }

        // Trim oldest entries so we never exceed MAX_ENTRIES.
        while (array.length() > MAX_ENTRIES) {
            array.remove(0);
        }

        prefs.edit().putString(KEY_LOG, array.toString()).apply();
    }

    /**
     * Return all recorded events, newest first, each pre-formatted as a
     * human-readable {@code "timestamp — event"} line ready for display.
     */
    public static List<String> getFormattedEntries(Context context) {
        List<String> out = new ArrayList<>();
        if (context == null) return out;

        SharedPreferences prefs =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        JSONArray array = readArray(prefs);

        for (int i = array.length() - 1; i >= 0; i--) {
            JSONObject entry = array.optJSONObject(i);
            if (entry == null) continue;
            long when = entry.optLong("t", 0);
            String event = entry.optString("e", "");
            out.add(FORMAT.format(new Date(when)) + "  —  " + event);
        }
        return out;
    }

    /** Number of events currently stored. */
    public static int size(Context context) {
        if (context == null) return 0;
        SharedPreferences prefs =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return readArray(prefs).length();
    }

    /** Clear the entire log (e.g. after the parent has reviewed it). */
    public static synchronized void clear(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_LOG).apply();
    }

    private static JSONArray readArray(SharedPreferences prefs) {
        String raw = prefs.getString(KEY_LOG, "");
        if (TextUtils.isEmpty(raw)) return new JSONArray();
        try {
            return new JSONArray(raw);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }
}
