# FCMGuard-HyperOS

Minimal no-root helper for improving Google FCM push reliability on Xiaomi HyperOS devices where PowerKeeper/Greezer may rewrite `Settings.System.MILLET_NO_RESTRICT_APP`.

## v1.3.0

- Default key is prefilled: `MILLET_NO_RESTRICT_APP`
- Default required item is prefilled: `com.google.android.gms`
- Refreshed Google-inspired card UI
- Language selector: **Follow system / English / 简体中文**
- Transparent status/navigation bars for gesture-bar immersion
- Keeps existing comma-separated whitelist entries and only restores the required item when missing
- Background watchdog + 30-second fallback check
- Manual **Wake FCM now** and **Open FCM diagnostics** actions

## Setup

1. Install the APK.
2. Open FCM Guard. The HyperOS key and Google Play services package are filled automatically.
3. Tap **Grant Modify system settings** and enable the permission.
4. Tap **Repair now**.
5. Tap **Start automatic protection**.
6. In HyperOS, enable **Autostart** and set FCM Guard battery policy to **No restrictions**.
7. Keep Developer options / USB debugging / Wireless debugging off if you want to minimize banking-app compatibility risk.

## Permissions / design

FCM Guard does not require root, Shizuku, persistent ADB, Accessibility, VPN, screen overlay, or device-admin privileges. It uses Android's user-grantable **Modify system settings** access and a foreground watchdog service.

## Build

GitHub Actions builds a debug APK for every push to `main`. Open **Actions → Build APK** and download `FCMGuard-debug-apk`.

The CI uses a persistent debug signing key cache so builds from the repository can update one another instead of producing conflicting signatures.
