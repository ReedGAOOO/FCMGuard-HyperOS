# FCMGuard-HyperOS

Minimal no-root helper for improving Google FCM push reliability on Xiaomi HyperOS devices where the system may rewrite a comma-separated Settings.System whitelist.

## Why this exists

On some China-region HyperOS builds, Google Play services can fall out of the system's no-restrict list after PowerKeeper/Greezer policy refreshes. When that happens, the FCM connection may be frozen or dropped while the screen is off.

FCM Guard is intentionally small and does **not** use root, Shizuku, persistent ADB, Accessibility, VPN, overlay, or device-admin privileges.

It only uses Android's user-grantable **Modify system settings** special access and a foreground watchdog service.

## Configure for the tested HyperOS setup

In the app, enter:

- **System setting key:** `MILLET_NO_RESTRICT_APP`
- **Required comma-list item:** `com.google.android.gms`

Then:

1. Tap **Save configuration**.
2. Tap **Grant Modify system settings** and allow FCM Guard.
3. Tap **Repair now** once.
4. Tap **Start automatic protection**.
5. In HyperOS app settings, enable **Autostart** for FCM Guard and set battery policy to **No restrictions**.
6. Keep Developer options, USB debugging, Wireless debugging, Shizuku, root, Accessibility, VPN and overlay permissions disabled unless you independently need them.

## What it does

- Reads the configured `Settings.System` key.
- Preserves every existing comma-separated item.
- Adds the configured required item only if missing.
- Observes System-setting changes while the foreground service is alive.
- Rechecks every 30 seconds as a fallback.
- Restarts after boot if HyperOS allows app autostart.

## Important behavior

The app is deliberately generic: the key and required item are user-configured rather than hard-coded.

If the configured setting disappears completely before FCM Guard has ever seen a valid value, the app will only be able to recreate the required item. For the tested device, set the key while the original value still exists whenever possible so the current list can be preserved.

## Build APK

GitHub Actions builds a debug APK on every push to `main`.

Open **Actions → Build APK**, choose the latest successful run, then download the artifact named:

`FCMGuard-debug-apk`

The contained APK is:

`app-debug.apk`

## Verification

After installation and configuration, use Android's FCM diagnostics code:

`*#*#426#*#*`

A healthy connection normally shows `Server: Connected`. For real validation, leave the phone locked for an extended period and check whether the connection time continues to accumulate and whether push notifications arrive without waking the phone manually.

## Banking-app compatibility

This project is designed to avoid the mechanisms that commonly trigger mobile-banking warnings: no persistent ADB, Wireless debugging, Shizuku, root, Accessibility service, VPN, screen-sharing or overlay permission is required.

Banking apps use private and changing risk rules, so compatibility can never be guaranteed. If a banking app objects, stop FCM Guard, revoke **Modify system settings**, and uninstall it before troubleshooting the bank app.

## License

No license has been selected yet.
