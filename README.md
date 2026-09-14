# FCMGuard-HyperOS

A minimal no-root helper for improving Google FCM push reliability on Xiaomi HyperOS devices where PowerKeeper/Greezer may rewrite `Settings.System.MILLET_NO_RESTRICT_APP` and remove Google Play services from the no-restrict list.

## Highlights

- No root, Shizuku, persistent ADB, Accessibility, VPN, overlay, or device-admin privilege
- Default HyperOS key: `MILLET_NO_RESTRICT_APP`
- Default required package: `com.google.android.gms`
- Preserves existing comma-separated whitelist entries and only restores the required item when it is missing
- Event-driven `ContentObserver` on the exact setting URI
- 30-minute in-process fallback check that does not deliberately wake a sleeping device
- FCM reconnect/heartbeat is sent only after a real repair or when manually requested
- Optional persistent notification / foreground-service mode for maximum survival reliability
- Quiet background mode when the persistent notification is disabled
- Follow-system / English / 简体中文 UI
- Full edge-to-edge UI with safe top status-bar/cutout and bottom gesture-navigation insets
- Flat, shadow-free controls
- Modern tall-screen compatibility while retaining `targetSdk 22` for Xiaomi private-setting compatibility

## Why targetSdk 22?

FCM Guard must write Xiaomi's private `Settings.System` entry. Modern Android restricts writes to non-public system settings even when the user grants **Modify system settings**. Keeping the app on the legacy compatibility path allows the same user-grantable mechanism used by tools such as SetEdit, without requiring root or shell-level privileges.

The app still compiles against a modern Android SDK.

## How it works

1. Watch `Settings.System.MILLET_NO_RESTRICT_APP`.
2. Read the current comma-separated package list.
3. If `com.google.android.gms` is already present, do nothing.
4. If it is missing, preserve the current list and append it.
5. After a real repair, send a best-effort FCM/MCS reconnect heartbeat.
6. If no settings event arrives, perform a lightweight in-process fallback check every 30 minutes.

## Setup

1. Install the APK.
2. Open **FCM Guard**.
3. Grant **Modify system settings** when prompted.
4. Tap **Repair now** once.
5. Enable **Automatic protection**.
6. In HyperOS, enable **Autostart** and set FCM Guard battery policy to **No restrictions**.
7. For maximum reliability, keep **Persistent notification** enabled. You can disable it for a cleaner notification shade, but HyperOS may be more likely to terminate the background service.
8. Keep Developer options / USB debugging / Wireless debugging off if you want to minimize banking-app compatibility risk.

## Power behavior

FCM Guard is designed to remain idle almost all of the time. It listens only to the exact HyperOS whitelist setting and does not poll every few seconds. The fallback check runs every 30 minutes only while the process already exists and does not use a WakeLock or AlarmManager to wake a sleeping phone.

## Compatibility notes

This project is intentionally targeted at the current HyperOS behavior observed around `MILLET_NO_RESTRICT_APP`. Xiaomi may change PowerKeeper/Greezer internals or the setting name in future firmware releases.

The app opts into resizable/tall-display layouts so the legacy target SDK does not cause letterboxing on modern devices.

## Build

GitHub Actions builds a signed debug APK for every push to `main`.

Open **Actions → Build APK** and download the `FCMGuard-debug-apk` artifact.

The workflow reuses a persistent CI signing key cache, so repository builds can update one another without changing signatures.

## Disclaimer

This app modifies an Android system setting related to HyperOS background restrictions. It is provided as-is and may stop working if Xiaomi changes its implementation. Use it at your own risk.

## License

MIT License. See [LICENSE](LICENSE).
