# Changelog

All notable changes to OverMobile are recorded here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Added
- **Save / Cancel bar at the top of Settings.** The Save button used to sit
  at the very bottom of a long list. A bar now stays pinned above the
  settings while you scroll: it says "Unsaved changes" (amber) once you edit
  something, Save validates and stores the miner and OverManager fields, and
  Cancel puts them back to the last saved values. Both stay disabled while
  nothing has changed. The old bottom Save button is gone.
- **Unsaved-changes prompt.** Leaving Settings (Dashboard, Logs, About, or
  the system back button) with unsaved edits asks whether to Save, Discard
  or Keep editing. System back from Settings now returns to the Dashboard
  instead of leaving the app.
- **Remove pairing.** While paired, the OverManager card has a red Remove
  pairing button. After you confirm, the phone stops reporting to
  OverManager and stops taking its commands until you pair again; mining
  keeps running. The card switches to "Pairing required" straight away.
- **Re-pair with a new code** while still paired. The link opens the
  pairing code field without dropping the current pairing; a successful
  pair replaces it, and Cancel re-pair closes the field again.
- **Pairing throttle countdown.** When OverManager refuses pairing after too
  many wrong codes (HTTP 429), the Pair button is disabled and an amber
  "Too many attempts. Try again in 0:47" line counts down to when you can
  try again.
- **Hint when Resume is set too close to Throttle.** In Settings, if the
  resume temperature is less than 6 °C below the throttle temperature, an
  amber note under the sliders warns that the phone may stay throttled and
  suggests leaving 8–10 °C between them. It's advice only; you can still
  save.

### Changed
- **Pool URL typos are tidied before checking.** Surrounding spaces are
  trimmed and `stratum+tcp//host:port` (missing colon) is corrected to
  `stratum+tcp://host:port` when you save or start mining.
- **Dark system splash on Android 12+.** The launch splash background now
  matches the app's dark theme instead of flashing a light screen.
- **New OverMobile app icon.** OverMobile has its own mark instead of the
  company logo: the OverBuild Labs faceted hexagon holding a purple phone
  with the company cube inside. Same family as the OverManager icon.
  Launcher (adaptive, legacy and round) and the Play Store image are
  regenerated from `docs/brand/overmobile-mark.svg`. The splash screen, top
  bar and About card now show the same mark (as a vector, sharp at any
  size), the status-bar notification uses a matching silhouette instead of
  Android's generic gear, and Android 13+ themed icons are supported.
- **Pool moves to `pool.overbuildlabs.com`.** New installs pre-fill
  `stratum+tcp://pool.overbuildlabs.com:5558`. Existing installs that saved
  `pool.proofofprints.com` are switched to `pool.overbuildlabs.com` (same
  port) on first launch after updating, before mining starts. Any other pool
  you saved is left alone.
- **In-app updates now check `overbuildlabs/OverMobile`** on GitHub instead
  of relying on the redirect from the old `proofofprints` repo path.
- **Warning colors are amber again.** The rebrand had turned every warning
  (temperature, battery, thermal state, pool connecting/error, pairing
  required, log warnings) violet. Warnings are amber, readouts (shares,
  hashes, difficulty) are Kaspa teal, and a connected pool is emerald.
  Violet is kept for the brand mark only.

### Security
- **Remote settings are validated.** Pool URL, wallet and worker sent by
  OverManager (`set_config`) go through the same checks as the Settings
  screen, and a remote change of the payout wallet is written to the in-app
  log. (OverManager commands are still unauthenticated on the LAN; signed
  commands are on the roadmap.)
- **Pairing is tied to its server.** Changing the OverManager server URL now
  drops the old server's credentials and asks for a new pairing code; before,
  the old server kept receiving telemetry and sending commands. The report
  URL returned at pairing can no longer point reports at a different host.
- **Updates are verified before install.** The downloaded APK must match the
  SHA-256 GitHub publishes for it and be signed by OverBuild Labs, and only
  assets from our own releases are accepted.
- **The OverManager API key is excluded from Android backups and device
  transfer**, so restoring a backup can't clone a paired miner.
- Pool and OverManager responses are size-limited, pairing QR codes are
  validated (http(s) URL, 6-digit code) and show the server they point to,
  and the wallet no longer appears in release logcat.

### Fixed
- **Changing the thread count no longer restarts the miner.** A thermal
  throttle, the restore after cooling, a new thread count from Settings or
  OverManager each used to stop and restart mining, so the hashrate dropped
  to 0 for a few seconds every time. Now only the worker threads change:
  mining carries on with the current job and the hashrate keeps reading
  normally.
- **Throttled threads come back once the phone cools.** After a thermal
  throttle, full threads were only restored if the temperature stayed at or
  under the resume temperature for 30 s straight. Normal sensor jitter of
  about a degree reset that wait, so a phone hovering right at the resume
  temperature could stay throttled for good. Small wobbles (up to 1 °C over
  the resume temperature) no longer reset the 30 s wait; a real rise, or
  reaching the throttle temperature, still does. The same applies to
  resuming after a heat pause.
- **The banner shows when you're throttled.** While running fewer threads
  because of heat, the banner says so (e.g. "Throttled: 4/8 threads — back
  to 8 below 68°C", then "Cooling — restoring 8 threads in 20s") instead of
  an unrelated note such as "External power mode".
- **OverManager sees a throttle that doesn't let go.** The phone reported
  `throttleState` "moderate" only while it was above the throttle
  temperature; once it cooled a little it reported "normal" or "light" even
  though it was still mining on half its threads, so OverManager's throttle
  alert never saw it. It now reports at least "moderate" until full threads
  are back ("critical" still wins). Telemetry also carries a new field,
  `throttledSinceMs`: when heat cut the thread count (epoch ms), or 0 when
  it isn't throttled, so OverManager can tell a brief throttle from a stuck
  one. Existing fields are unchanged.
- **Pairing state updates live.** When OverManager deletes the device, the
  phone's next report drops the credentials, but the card kept saying
  "Paired" and hid the code field until you edited a setting and saved. It
  now shows "Pairing required" and the code field as soon as that happens.
- **A wrong pairing code says so.** OverManager's "Invalid pairing code" now
  shows "Wrong pairing code. Check the code in OverManager." instead of
  claiming the code expired. A failed code while re-pairing no longer stops
  reporting under the existing pairing.
- **Saving Settings while mining applies to the running session.** A new
  thread count used to do nothing until Stop / Start, and the dashboard
  then showed e.g. `4/6` as if the phone were throttling. It now takes
  effect within a second, the same way OverManager's thread command does.
  Thermal protection still wins: while throttled, a lower count applies at
  once and a higher one waits for the normal cool-down restore. A new pool,
  wallet or worker reconnects the session ("Reconnecting with the new pool
  settings") instead of being ignored until the next Start.
- **Thread counts are capped at what the phone can run.** Counts from
  OverManager (which accepts up to 16) are clamped to the number of cores,
  at most 8 (the miner's limit), and the clamped value is what the app and
  OverManager show.
- **A clean Settings screen keeps up with remote changes.** If OverManager
  changed a setting while Settings was open with nothing edited, a later
  Save wrote the old value back. Unsaved edits are still never replaced.
- **Protection options now go through Save / Cancel.** Thermal and power
  settings (thermal protection, warn / throttle / pause / resume
  temperatures, external power mode, require charging, battery cutoff) used
  to apply the instant you touched them, so the Save bar never lit up and
  Cancel couldn't undo them. They are now staged like the rest of Settings:
  they mark the screen as unsaved, Cancel and Discard revert them, and the
  miner only uses the new limits after Save.
- **System Back from Settings now asks about unsaved changes.** Two Back
  handlers conflicted, so the prompt was skipped and Back left Settings
  with the edits still on screen.
- **Works with public pools such as HeroMiners.** The login now sends the
  standard password field (pools rejected it with "Invalid params"), and the
  pool's nonce prefix is picked up whether it arrives as `set_extranonce`,
  `mining.set_extranonce`, or in the subscribe reply — previously it was
  ignored, so shares would have been rejected.
- **"Require charging" pauses now hold.** With the phone unplugged, the
  miner restarted every 30 seconds and stopped again straight away. Each
  pause now remembers its cause (heat, low battery, unplugged) and resumes
  only when that cause clears; starting a session unplugged no longer
  mines for a few seconds before pausing.
- **Mining resumes after a low-battery pause once the phone charges**
  past the cutoff + 5 %. Before, it stayed paused until the battery reached
  twice the cutoff.
- **The dashboard temperature uses your protection thresholds.** It turned
  amber at 45 °C and red at 55 °C regardless of settings; it now follows
  the warn / throttle / pause temperatures.
- **"Shares" counts what the pool accepted.** The dashboard, notification
  and OverManager's accepted-shares figure counted every share found,
  including rejected and lost ones.
- **A pool that rejects the wallet or worker name now says so** in the
  status card, notification and log, instead of hashing on silently. A
  share answered with `false` and no error now counts as rejected.
- The Back button on Settings, Logs and About returns to the dashboard
  instead of closing the app.
- **Share difficulty above 1 was ignored.** The target was computed from an
  integer-truncated multiplier, so any pool difficulty above 1 produced the
  difficulty-1 target (the pool would reject almost every share) and
  fractional difficulties were off by up to a few percent. Now exact.
- **The miner now reconnects after the pool drops the connection.** A clean
  close by the pool left the app hashing a stale job or stuck in error until
  Stop/Start.
- A remote pool change from OverManager no longer stops mining for good.
- Double-tapping Start, or a remote start while connecting or paused, no
  longer opens a second pool connection and a second wake lock.
- Thermal throttling no longer resets the accepted-shares count.
- Devices without a readable CPU temperature resume after a pause.
- Stopping no longer risks freezing the app when the pool connection is dead;
  shares are sent off the hashing threads.
- Fixed crashes from a malformed pairing QR, an over-long pool extranonce,
  an exception in the native share callback, and the thread slider on
  one-core devices. Start/stop from several threads at once is now safe in
  the native engine.
- Fresh installs keep the prefilled default pool (it was wiped a second after
  launch); the thread slider tops out at the engine's 8 threads.
- A release with no notes no longer hides the update; rotating during an
  update download no longer cancels it.
- Pairing from a fresh install starts reporting right away.
- The wake lock is renewed for sessions longer than 4 hours.

### Build
- Native library linked for 16 KB memory pages (Android 15+); static C++
  runtime. Android 14 foreground-service type is declared.

## [1.2.0] — 2026-05-29
### Changed
- **Rebrand to OverBuild Labs.** The app is now **OverMobile** by **OverBuild Labs** — matches the website, OverManager desktop app, and OverMiner hardware line. Application identity (`applicationId`), on-disk preferences, and signing keypair are unchanged: existing PoPMobile installs upgrade to OverMobile in place with no loss of wallet, pool, pairing, or settings state.
- New OverBuild Labs palette: emerald-500 primary (`#10B981`), violet-500 accent (`#8B5CF6`), near-black surfaces (`#0C0C0F` / `#16161B`). The Compose colorScheme and system status/navigation bars now follow the brand palette; one-off accent literals scattered through the dashboard cards still match the previous Kaspa-teal style and will migrate in a follow-up.
- New OverBuild Labs launcher icon (adaptive + legacy + round) generated from the hex/cube logo.
- Worker-name default for fresh installs is now `OverMobile` (existing installs keep their saved value).
- Device telemetry reports `manufacturer="OverBuild Labs"` to OverManager (previously `"Proof of Prints"`).

### Deferred to a later release
- GitHub repository URLs still point at `github.com/proofofprints/...` — the org has not been renamed yet.
- `pool.proofofprints.com`, `support@proofofprints.com`, and `www.proofofprints.com` are unchanged pending infrastructure migration.

## [1.0.14] — 2026-04-21
## [1.0.13] — 2026-04-21
### Fixed
- **Landscape mode scrolling.** Rotating the phone to landscape hid the **START MINING** button below the fold with no way to scroll to it. The dashboard now switches to a scrollable layout when the device is in landscape while keeping the portrait design (weighted spacer, button anchored near the bottom) intact.
- **Decimal separator in numeric displays.** Hashrate, hash total, difficulty, notification text, and update-size formatting were rendering with locale-dependent separators — e.g. `1,52 MH/s` instead of `1.52 MH/s` on French/German/etc. systems. All user-visible number formats now explicitly use `Locale.US` so the decimal is always a period.

### Changed
- **Thermal status uses the OS's bucket directly** on Android 10+ (API 29+). Previously we translated `PowerManager.getCurrentThermalStatus()` into a synthetic CPU temperature and then ran that through our 45/50/55°C thresholds — a round-trip that lost fidelity. Now the OS bucket maps straight onto our `NORMAL / WARNING / THROTTLE / CRITICAL` state, so the THERMAL card reflects what the OS actually thinks regardless of whether raw sensor files are readable.
- **TEMP card falls back to battery temperature** when CPU thermal zones are locked down (common on retail Android where OEMs restrict `/sys/class/thermal/`). Battery sits next to the SoC on every phone, so its temperature is a decent proxy during mining — previously these devices showed `--`, now they show a useful number.
- **Battery percent falls back** to `Intent.ACTION_BATTERY_CHANGED`'s `EXTRA_LEVEL / EXTRA_SCALE` when `BatteryManager.BATTERY_PROPERTY_CAPACITY` returns −1 or 0 (some OEMs don't implement the newer API reliably). BATTERY should now read correctly on every Android phone.

## [1.0.12] — 2026-04-21
### Changed
- Start Mining validation toast now **prefixes the field name** so it's obvious which input is wrong. Examples: `Wallet: Must start with kaspa:`, `Pool: Port must be between 1 and 65535`, `Worker: Worker name is required`.
- **Inline errors in Settings no longer appear for empty required fields.** Format errors (`kaspa:xyz` too short, port missing, etc.) still show under the field. Blank-required state is caught by the Start Mining toast only — keeps the Settings panel from opening with three red messages on a fresh install.
- **Memoized validator results** per field so the regex doesn't re-run on every recomposition — trims work on the UI thread while the user is typing.

## [1.0.11] — 2026-04-18
### Added
- **Default pool URL** — Settings → Pool URL now pre-fills with `stratum+tcp://pool.proofofprints.com:5558` on first launch so a brand-new install can start mining by entering only a wallet address.
- **Inline validation** on the three miner configuration fields with red border + error text:
  - Pool URL must be `stratum+tcp://host:port`
  - Wallet address must begin with `kaspa:` (or `kaspatest:`) and be structurally plausible
  - Worker name must be non-empty and alphanumeric (plus `_`, `-`, `.`)
- **Start Mining validation toast** — the button no longer silently does nothing when a field is invalid. Tapping it with bad input shows a toast naming the specific problem ("Wallet address is required", "Port must be between 1 and 65535", etc.) and refuses to start the service.

### Changed
- **OverManager Integration** header now reads "POPMANAGER INTEGRATION (Optional)" so new users know pairing isn't required to mine.
- Section description rewritten: "Pair with OverManager to monitor your mobile mining."
- Removed the "Forget this device and re-pair" button. Deleting a device on the OverManager side is now the canonical way to un-pair — the reporter's existing 401/404 handling already clears the local credentials and flips the app back to the pairing-code input without any user action needed here.

## [1.0.10] — 2026-04-18
### Notes
- Pipeline-verification release — no code changes. Exists so devices on v1.0.9 (the first build with the non-crashing progress bar) can finally exercise the in-app update flow end-to-end against a newer release.

## [1.0.9] — 2026-04-18
### Fixed
- **Actually fixed the Download-button crash** (v1.0.7 misdiagnosed it). The `NoSuchMethodError` on `KeyframesSpec.at(Object, Int)` wasn't from R8 after all — it was a real version mismatch between `material3` and `animation-core` inside our pinned `compose-bom`. Material3's `LinearProgressIndicator` calls an animation-core method that didn't ship in the version bundled at runtime. Replaced the Material3 indicator with a plain `Box` progress bar we draw ourselves, sidestepping the entire `KeyframesSpec` code path.

## [1.0.7] — 2026-04-18
### Attempted fix (did not fully work)
- Disabled R8 minification for release in the belief that R8 was stripping a Compose method. The NoSuchMethodError on `KeyframesSpec.at(Object, Int)` persisted even without minification, revealing the real cause was a library version mismatch rather than shrinking. Kept the disabled minification — it's still the right default for a ~2 MB APK — but the actual Download-button fix landed in v1.0.9.

## [1.0.6] — 2026-04-17
### Fixed
- **Tapping "Open Settings" on the install-permission prompt no longer marks the update as dismissed.** In v1.0.4/v1.0.5 the Settings detour closed the dialog through the same code path as "Later", which recorded the version as declined — so after granting the permission and returning, **Check for updates** would report "You're on the latest version" even though an update was pending. The dialog now separates "close without declining" (Open Settings, successful install, retry) from "user declined this version" (Later, Cancel).
- `Check for updates` (manual, `force = true`) now bypasses the dismissed-versions list as well as the 24-hour throttle. If you explicitly ask, we always show you what's available.

## [1.0.5] — 2026-04-17
### Added
- Update check now also runs on each `ON_RESUME` (when the app returns to the foreground), not only on cold launch. The 24-hour throttle inside `UpdateChecker` still applies, so keeping the app open for days no longer hides new releases behind a force-quit.

### Notes
- Primarily a pipeline-verification release after v1.0.4's install-flow fixes — a device on v1.0.4 should be offered this build through the in-app updater.

## [1.0.4] — 2026-04-17
### Fixed
- In-app update flow got stuck when the user hadn't yet granted "Install unknown apps" permission — the Settings screen would open but our dialog had no way to resume afterward. Now we **pre-check** the permission before kicking off the download and, if missing, show a clear "Open Settings" explainer that cleanly closes the dialog so the user can retry cleanly after granting.
- Install intent is now also fired by a manifest-registered `BroadcastReceiver` listening for `DownloadManager.ACTION_DOWNLOAD_COMPLETE`, so the install dialog still appears even if the app was killed during the download. The in-app flow and the receiver dedupe via a shared pending-download-id SharedPref — whichever path gets there first consumes the trigger.

## [1.0.3] — 2026-04-17
### Added
- `CHANGELOG.md` to record release history outside the GitHub Releases page.
- "Last checked X ago" line under the version in **Settings → About**, so you can tell at a glance when the app last pinged GitHub for a newer release.

### Notes
- Also serves as the end-to-end verification release for the in-app update checker introduced in v1.0.2 — devices on v1.0.2 will be offered this build.

## [1.0.2] — 2026-04-17
### Added
- In-app update checker backed by the GitHub Releases API. Checks once per 24 h on app launch and on demand via **Settings → About → Check for updates**. Downloads the APK via `DownloadManager`, hands off to the system installer through a `FileProvider`. OS signature verification means only APKs signed with the same release keystore can install over an existing OverMobile.

### Changed
- About panel version is now read from `BuildConfig.VERSION_NAME` instead of being hardcoded.

## [1.0.1] — 2026-04-17
### Changed
- Internal Kotlin package renamed `com.proofofprints.kasminer` → `com.proofofprints.popmobile`; JNI bridge function names, native library name (`libpopmobile.so`), and build namespace updated accordingly.
- `applicationId` intentionally **kept** on the old namespace so existing v1.0.0 installs upgrade in place — settings, logs, and OverManager pairing are preserved.
- Project-layout diagrams in `README.md` and `CONTRIBUTING.md` refreshed.

## [1.0.0] — 2026-04-17
### Added
- First public release.
- Native kHeavyHash miner (`arm64-v8a`, `armeabi-v7a`, `x86_64`) ported from KASDeck.
- Stratum v1 client with extranonce handling, 10-second connect timeout, and cancellable retry loop.
- Pool status UX with specific error reasons (`REFUSED` / `TIMED OUT` / `DNS FAILED` / `UNREACHABLE` / `NO NETWORK`).
- Thermal monitoring with auto-throttle.
- Live dashboard: hashrate, shares, total hashes, thread count, temperature, battery, thermal state.
- QR code scanning for the Kaspa wallet address (bundled ZXing).
- Foreground service with persistent notification and one-tap Stop.
- Mining log retaining the last four hours of stratum + share events.
- OverManager pairing with per-device API keys, telemetry every 30 s, and remote commands (`set_config`, `set_threads`, `start`, `stop`, `restart`) applied and acknowledged.

[1.0.14]: https://github.com/proofofprints/PoPMobile/releases/tag/v1.0.14
[1.0.13]: https://github.com/proofofprints/PoPMobile/releases/tag/v1.0.13
[1.0.12]: https://github.com/proofofprints/PoPMobile/releases/tag/v1.0.12
[1.0.11]: https://github.com/proofofprints/PoPMobile/releases/tag/v1.0.11
[1.0.10]: https://github.com/proofofprints/PoPMobile/releases/tag/v1.0.10
[1.0.9]: https://github.com/proofofprints/PoPMobile/releases/tag/v1.0.9
[1.0.7]: https://github.com/proofofprints/PoPMobile/releases/tag/v1.0.7
[1.0.6]: https://github.com/proofofprints/PoPMobile/releases/tag/v1.0.6
[1.0.5]: https://github.com/proofofprints/PoPMobile/releases/tag/v1.0.5
[1.0.4]: https://github.com/proofofprints/PoPMobile/releases/tag/v1.0.4
[1.0.3]: https://github.com/proofofprints/PoPMobile/releases/tag/v1.0.3
[1.0.2]: https://github.com/proofofprints/PoPMobile/releases/tag/v1.0.2
[1.0.1]: https://github.com/proofofprints/PoPMobile/releases/tag/v1.0.1
[1.0.0]: https://github.com/proofofprints/PoPMobile/releases/tag/v1.0.0
