# Nighttime FCM protection implementation

## Components

- `NightProtectionEngine`: injectable Java state machine and exact-token parser.
  `contains/append/remove/reload` implement the vendor delimiter behavior.
  `configure` persists opt-in/baseline and routes enable/disable;
  `maintain` performs bounded repair/initialization and recovery;
  `writeStable` rereads and verifies the exact value;
  `cleanup` preserves preexisting entries and requires consent when ownership is uncertain;
  `check` is read-only, `testBroadcasts` sends requests only, `clearLogs` clears the bounded log.
- `NightFcmProtection`: application-scoped adapter with a serial executor, ordinary
  Settings calls, JSON preference persistence, timestamped reports and lifecycle-safe
  main-thread listeners. `persist/restore` preserve ownership, cleanup and log history.
  `observer/serviceFailure` report actual observer registration/service errors.
  `publish` never promotes configuration to proven runtime protection.
- `NightProtectionUi`: XML View bindings, clipboard actions and explicit uncertain
  cleanup confirmation; `resume/pause` register/remove listeners.
- `GuardService`: separately registers each enabled key, debounces 400 ms and shares
  its existing 30-minute fallback. MILLET repairs now use a HandlerThread.
- `BootReceiver`: asynchronous preference initialization before deciding whether
  either enabled feature needs the service; finishes its pending receiver result.
- `FcmReconnect.requestGcmReconnect`: explicit GMS request whose failures propagate;
  `kick` retains both original heartbeat actions and both original target packages.
- `MainActivity`: wires the card and reconciles the service against either switch.

No manifest permission, target SDK, app version, signing, wrapper or CI change.
Only test dependencies (JUnit/Mockito) were added. Other seven locales use English
fallback for the new card; technical logs/errors remain English in every locale.

## Automated verification

The upstream branch has no checked-in Gradle wrapper. Run the layout checker
and use an installed compatible Gradle distribution with JDK 21. The preparation
checkout used Gradle 9.6.1 and JDK 21.0.10, with an ignored local signing override
because the upstream debug key is generated/restored only in CI:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
python tools/check_layout_profiles.py
gradle :app:testDebugUnitTest :app:assembleDebug --stacktrace
```

The upstream minSdk 21 and targetSdk 22 are retained. The new permission probe
uses the pre-M manifest permission path below API 23. Gradle also reports
existing deprecated features ahead of Gradle 10; Java reports deprecated API usage.
`:app:lintDebug` was also run: it is blocked by four preexisting errors (two
API-27 navigation-bar style attributes without qualifiers, the WRITE_SETTINGS
protected-permission diagnostic, and the deliberately retained old target SDK).
The new feature has no remaining Lint findings. The seven-locale English fallback
is intentionally scoped with MissingTranslation suppression on its own base
resource file; no global Lint suppression/baseline was introduced.
Tests cover null/empty/exact/substring/duplicate/untrimmed tokens, preservation,
trailing reload, rejected PUT, different read-back including still-present GMS,
repeated/self observer-equivalent callbacks, before/after-PUT races, backoff,
disable/empty cleanup/preexisting ownership, uncertain cleanup consent, failed
cleanup/re-enable gating, failed broadcasts, unavailable frozen/socket evidence,
restart/reload, read-only check, broadcast rate limiting, bounded logs and durable
ownership failure. Separate Mockito regressions exercise the original MILLET
parser, healthy no-op, preservation/deduplication and permission denial.

Engine tests simulate callbacks; actual Android observer delivery/debounce needs
device evidence. The layout checker statically covers 320/360/393/411/430/480 dp;
it does not replace visual checks on every width or font scale.

## Historical full-fork device evidence (2026-10-17)

The following observations predate extraction into this independent upstream PR.
This exact standalone branch has not been installed or tested on a phone.

Device 2509FPN0BC, China OS4.0.2.0.XPBCNXM, API 37. Debug APK updated via `adb
install -r`, retaining data; no shell grant or elevated app execution used.
WRITE_SETTINGS was already allowed. GMS was already present in the game list.
App enable changed `com.google.android.gms` to `com.google.android.gms_` using
ordinary app Settings PUT, verified read-back, dispatched both requests and showed
observer running plus actual runtime effect unverified. Recheck retained the value
and did not send another request. Traditional Chinese Debug fields showed actual
frozen state unavailable and FCM Socket inaccessible. A dark-mode screenshot was
inspected. Disabling original MILLET protection while night protection remained
on left GuardService running. Turning night protection off as well stopped the
service and retained preexisting GMS. Original MILLET protection was reenabled.
A force-stop followed by explicit relaunch preserved the enabled option and
ownership, reread the list, and toggled the trailing underscore once to request
cache reload in the new process. The force-stopped interval itself was unmonitored.

These checks do NOT establish actual thaw, socket connectivity, push delivery,
overnight reliability/battery, clone recovery, reboot/OTA behavior, denied-permission
behavior on this phone, missing-GMS real-device repair/cleanup or every screen width.
No controlled deep-idle experiment, package-list overwrite, reboot or ROM modification
was performed on the user's phone. ADB was used as a development/install/read-only
verification tool and to tap the app UI, not as a feature dependency.

## Manual device procedure

1. Grant Modify system settings using the existing permission button if needed.
2. Enable the independent night switch, expand Debug and inspect read/write and
   observer status. Treat success as configuration only.
3. Recheck repeatedly: the raw value, repair time and broadcast history should not
   change except new read-only check logs.
4. Use Test thaw/reconnect once; inspect both individual dispatch errors/results.
   Repeated requests within 10 seconds are rate limited and logged.
5. Copy the report/log, then clear logs and confirm the log is empty.
6. Switch original protection off/on independently and verify the night observer
   remains active while night protection is enabled. Try quiet/foreground modes.
7. Disable night protection; inspect cleanup pending, ownership and read-back.
   Preexisting GMS must remain. For uncertain app-added membership, only explicitly
   confirm removal after considering other users of the shared entry.
8. Relaunch and reboot tests should separately verify persisted options, initialization
   reload and actual service restart. The app cannot observe while force-stopped.
9. On a dedicated test phone, separately establish controlled night/deep-Doze baseline,
   owner/profile frozen state, socket state and actual push receipt using trusted
   external diagnostics. App dispatch results cannot substitute for those observations.

## Remaining inherent limitations

Settings lacks CAS, so no app can guarantee preservation against a writer racing
between its final read and PUT. Same-value shared ownership changes are unobservable.
Public SDK access cannot identify loaded OEM cache or GMS freezer/socket state.
Android/OEM may defer or kill the process, leaving an unprotected repair window.
A read of null alone cannot distinguish an absent setting from an unsupported ROM.
