# Airplane Lock – Parental Control

An Android parental-control app that prevents a child from disconnecting or shutting
down a monitored device. It watches for **Airplane Mode**, **Mobile Data off**, and
**Power-off / Restart** actions, and blocks them behind a parental password. A
separate short "travel / bypass" code lets the parent temporarily lift all
restrictions (for example, on an actual flight).

> Built for personal/parental use. Some capabilities require one-time setup via ADB
> (see [Setup](#setup)).

## Features

- **Airplane Mode lock** – when airplane mode is turned on, a full-screen password
  prompt appears. The child must enter the parental password or turn airplane mode
  back off.
- **Mobile Data lock** – turning mobile data off triggers the same password prompt.
- **Power-off / Restart lock** – an accessibility service detects the system
  power menu (power off, restart, shut down) and requires the password to proceed.
- **Travel / Bypass mode** – a separate short code temporarily disables all
  restrictions; turning it back off requires the main parental password.
- **Always-on Battery Saver** – the app keeps battery saver enabled (requires
  `WRITE_SECURE_SETTINGS`, granted once via ADB) and silently re-enables it if
  turned off.
- **Uninstall protection** – optional Device Admin so the app cannot be uninstalled
  from Settings without first disabling Device Admin (which is password-protected).
- **Brute-force protection** – after 5 wrong password attempts the lock screen is
  disabled for 60 seconds.
- **Activity Log (new)** – every blocked airplane-mode, mobile-data and power-off
  attempt, plus failed unlocks, lockouts and bypass activations, is recorded with a
  timestamp. Parents can review the history (and clear it) from the main screen via
  **View Activity Log**. The log is capped at the 100 most recent events and stored
  locally in the app's private preferences.

## Tech Stack

- **Language:** Java
- **Platform:** Android (`minSdk 26` / Android 8.0+, `targetSdk 34`, `compileSdk 34`)
- **Build system:** Gradle (Android Gradle Plugin 8.2.0)
- **Libraries:** AndroidX AppCompat & Core; `org.json` (bundled with the Android platform)
- **Key Android APIs:** `DeviceAdminReceiver`, `AccessibilityService`, foreground
  `Service`, `BroadcastReceiver`, `ContentObserver`, `Settings.Global`

## Setup

### Prerequisites
- Android Studio (or Gradle + Android SDK)
- A device/emulator running Android 8.0 (API 26) or higher
- `adb` available on your PATH for the one-time secure-settings grant

### Build & install
```bash
# From the project root
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```
Or simply open the project in Android Studio and press **Run**.

### One-time ADB grant (for Battery Saver control)
```bash
adb shell pm grant com.parentalcontrol.airplanelock android.permission.WRITE_SECURE_SETTINGS
```

## Usage

1. Open the app and **set a parental password** (minimum 4 characters).
2. (Recommended) Tap **Activate Device Admin** to prevent uninstallation.
3. Grant **Display over other apps** when prompted (needed to show the lock screen).
4. Enable the **accessibility service** in Android Settings to activate power-off
   blocking.
5. Optionally set a short **bypass code** for travel.
6. From now on, attempts to enable airplane mode, disable mobile data, or power off
   the device will require the parental password.
7. Tap **View Activity Log** at any time to see what was attempted.

### Bypass / Travel mode
- On the lock screen, tap **Use Travel / Bypass Code** and enter the bypass code to
  temporarily disable all restrictions.
- To re-enable restrictions, open the app → **Travel / Bypass Mode** → **Turn Bypass
  Mode OFF**, then confirm with the main parental password.

The default bypass code shipped on first launch is documented in `BYPASS_CODE.txt`.
**Change it** from the app's *Travel / Bypass Mode* section and keep it private.

## Project Structure

```
AirplaneLock/
├── build.gradle                # Root Gradle config (AGP plugin)
├── settings.gradle             # Module includes
├── BYPASS_CODE.txt             # Default travel/bypass code & instructions
└── app/
    ├── build.gradle            # App module config & dependencies
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/parentalcontrol/airplanelock/
        │   ├── MainActivity.java         # Setup UI: password, bypass, admin, activity log
        │   ├── PasswordActivity.java     # Full-screen lock prompt
        │   ├── MonitorService.java       # Foreground service: airplane/data/battery observers
        │   ├── PowerLockService.java     # Accessibility service: power-menu detection
        │   ├── AirplaneModeReceiver.java # Boot + airplane-mode broadcast receiver
        │   ├── AdminReceiver.java        # Device Admin receiver (uninstall protection)
        │   └── AccessLog.java            # Tamper / activity log (new)
        └── res/
            ├── layout/                   # activity_main, activity_password
            ├── values/                   # strings, styles
            ├── xml/                      # device_admin, accessibility_service
            └── drawable/                 # ic_launcher
```

## Permissions

| Permission | Why it is needed |
|---|---|
| `RECEIVE_BOOT_COMPLETED` | Restart the monitor service after reboot |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | Keep monitoring running |
| `SYSTEM_ALERT_WINDOW` | Show the lock screen over other apps |
| `BIND_DEVICE_ADMIN` | Uninstall protection |
| `READ_PHONE_STATE` | Mobile-data state checks |
| `WRITE_SECURE_SETTINGS` | Toggle battery saver (granted once via ADB) |

## Disclaimer

This app is intended for legitimate parental control of devices you own or are
authorized to manage. Use it responsibly and in accordance with applicable laws.

## License

No license file is currently included. All rights reserved by the repository owner
unless a license is added.
