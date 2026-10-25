# OverMobile roadmap

The prioritised backlog. Each item is sized to be picked up by one session or
agent: it says what to change, where, and how to know it's done.

**Working an item:** read [`CLAUDE.md`](../CLAUDE.md) (invariants!), branch
`claude/<id>-<slug>`, implement, update `CHANGELOG.md`, and in the same PR
move the item to *Done* at the bottom of this file with the PR number. Items
marked **cross-repo** need a matching change in
[OverManager](https://github.com/overbuildlabs/overmanager) — coordinate the
wire format first and keep OverMobile accepting the old format for at least
one release.

Priorities: **P0** security/data-loss · **P1** user-visible bugs ·
**P2** hardening/maintenance · **F** features.

---

## Hardening and bugs (from the [Oct 2026 audit](AUDIT-2026-10.md))

### R1 · P0 · Authenticate OverManager commands — cross-repo
Today any host that can answer the miner's `/report` POST on the LAN (ARP/DNS
spoofing) can send `set_config` and redirect the payout wallet. Validation now
blocks malformed values, not malicious ones.
- Preferred: at pairing, OverManager returns a per-device HMAC secret (or its
  public key). Each command carries `sig = HMAC(secret, id|type|params|deviceId|issuedAt)`;
  the device rejects unsigned, mis-signed, or stale (> 5 min) commands and
  remembers ids (already does).
- Alternative/also: OverManager serves HTTPS with a self-signed cert whose
  SHA-256 fingerprint is in the pairing QR; pin it in `PoPManagerReporter.httpPost`
  and drop `usesCleartextTraffic` (or narrow it with a `network_security_config`).
- Optional device-side guard: a "Lock payout wallet" setting that refuses
  remote wallet changes, and a notification whenever one is applied.
- Files: `popmanager/PoPManagerReporter.kt`, `service/MiningService.kt`
  (`handleSetConfig`), `AndroidManifest.xml`. Done when an unsigned
  `set_config` is acked `failed` and a signed one applies.

### R2 · P1 · Full 16 KB page-size support
ELF segments are aligned already. Remaining: AGP ≥ 8.5.1 (16 KB zip
alignment of uncompressed `.so`), pin `ndkVersion` to r27+ in
`app/build.gradle.kts`, Gradle wrapper to match. Verify with
`zipalign -c -P 16 -v 4 app-release.apk` and Android Studio's APK Analyzer.
Re-test the in-app update path afterwards (signing config unchanged).

### R3 · P1 · CI on every PR
GitHub Actions workflow: `./gradlew assembleDebug lint testDebugUnitTest`
on ubuntu with the Android SDK, plus `tools/typecheck/typecheck.sh`. Cache
Gradle. No signing secrets in CI for PRs. Done when a PR shows a green check.

### R4 · P1 · Unit tests for pure logic
JVM tests (`app/src/test/`) for `VersionCompare`, `FieldValidators`,
`PoolMigration`, `ApkVerifier.parseGithubDigest/safeFileName/isAllowedDownloadUrl`,
stratum message parsing (factor `handleNotify`/`handleSetExtranonce` parsing
into pure functions), and the report-interval clamp. Native: a small host
test (CMake `ctest` or the existing self-test vector) for
`set_target_from_difficulty` and the kHeavyHash test vector.

### R6 · P1 · Service start/stop robustness
- Run `stopMining`'s engine join off the main thread (it's bounded but can
  still stall the UI briefly).
- Handle the `START_STICKY` null-intent restart explicitly (restore
  reporter-only foreground state, don't auto-mine).
- `MainActivity`: unbind safely if `onStop` runs before
  `onServiceConnected`; null `miningService` on disconnect; call
  `startForegroundService` from the click handler, not the async bind callback.

### R7 · P1 · Install prompt when the app is in the background
`InstallReceiver.startActivity` is blocked on Android 10+ when the app isn't
foreground. Post a notification ("OverMobile update ready — tap to install")
whose `PendingIntent` opens the installer (after `ApkVerifier`).

### R8 · P2 · Pre-release versions in `VersionCompare`
`1.2.0-rc1` vs `1.2.0` compares equal, so RC testers aren't offered the
final. Treat a pre-release suffix as lower than the plain version. Add tests.

### R9 · P2 · Download updates to internal storage
On API 26–28 app-specific external storage is writable by other apps with
`WRITE_EXTERNAL_STORAGE`. Download with `HttpURLConnection` into `filesDir`
(progress is easy), switch the FileProvider path to `files-path`, keep
`ApkVerifier`. Removes the DownloadManager/receiver split.

### R10 · P2 · Strip debug logging from release
Enable R8 with a Compose-safe config (minify was disabled over a Compose
crash — see `app/build.gradle.kts`) and `-assumenosideeffects` for
`android.util.Log.d/v/i`, or route logging through a `BuildConfig.DEBUG`
wrapper. Check the share/job SHARE_DEBUG native logs too.

### R12 · P2 · Small lifecycle cleanups
Thermal diagnostics read sysfs on the main thread (move to IO);
`PoPManagerReporter.scope` is never cancelled (cancel in `MiningService.onDestroy`).

### R13 · P2 · Native engine leftovers
Clear `has_job` when the pool changes (stale job after a switch); reject or
log job ids > 64 chars instead of silently truncating; surface a failed
kHeavyHash self-test (refuse to mine, show an error); make debug builds keep
`-O3` for the hash code.

### R14 · P2 · Finish the naming cleanup
Gradle `rootProject.name` (`KASMobileMiner` → `OverMobile`; IDE-only, check
nothing keys off it); release asset name `overmobile-vX.Y.Z.apk` everywhere
(`docs/RELEASING.md`, README; the v1.2.0 asset was named `overminer-…`);
notification channel display name "KAS Mining" → "Mining" (keep the channel
id). Never touch the invariants in `CLAUDE.md`.

### R16 · P2 · Notification says "Idle" while connecting or paused
`MiningService.ensureRunningForReporting` checks `miningEngine.isRunning`;
reopening the app while a session is connecting or paused replaces the
notification with "Idle — reporting to OverManager". Check
`isSessionActive` instead.

### R17 · P3 · Display consistency
Notification always shows `H/s` (dashboard scales to kH/MH, and should say
`kH/s` not `KH/s`); the THREADS tile and thread warning use the error red;
battery reads 100 % before the service binds. One shared formatter.

### R18 · P3 · "connecting" status in telemetry — cross-repo
OverManager sees `stopped` while the phone is connecting or waiting for its
first job. Add a `connecting` status once OverManager accepts it.

---

## Performance

### P1 · Per-thread counters only
Each hash does two atomic adds on shared globals (`total_hashes`,
`hash_count_for_rate`) on one cache line, plus the per-thread counter.
Drop the shared ones and derive totals from `sum_thread_hashes()`; use
`__atomic_load_n` for 64-bit reads (tearing on armeabi-v7a). Measure before/after.

### P2 · NEON matrix multiply
The 64×64 matrix–vector step in `kheavyhash.c` is the hot loop. A NEON
(`arm_neon.h`) version for arm64-v8a, guarded by the existing self-test
vector, is the biggest single hashrate win available.

### P3 · Auto-tune threads
A short benchmark mode that tries 1..N threads and picks the best
hashes-per-°C (or hashes/s under the user's throttle threshold). Show the
result; let the user accept it.

---

## Features

Ideas ranked by user value ÷ effort. Each needs a short design note in the
PR before code.

### F1 · Charging and schedule rules
"Mine only while charging", "only on Wi-Fi", "only between 23:00–07:00",
"stop at battery temperature X". Builds on `MiningPreferences` and the stats
loop. High value for overnight/bench rigs.

### F2 · Battery-friendly charge cap guidance
Detect when the phone sits at 100 % on the charger for hours and suggest
enabling the OEM's charge limit / external-power mode. Show battery
temperature on the dashboard.

### F3 · Stratum over TLS + backup pool
Support `stratum+ssl://` (SSLSocket, system trust) when the pool offers it,
and an optional backup pool URL with automatic failover after N failed
reconnects. Touches `StratumClient`, `FieldValidators`, Settings UI.

### F4 · Hashrate and share history
Use the native 15-minute ring buffer (and persist a longer, coarse history)
to draw a hashrate chart, accepted/rejected share timeline, uptime and
average temperature on the dashboard.

### F5 · Home-screen widget and Quick Settings tile
Start/stop and live hashrate without opening the app. Needs the service to
expose state via a `StateFlow` (see E2).

### F6 · Notification upgrades
Pause/Resume action alongside Stop; show hashrate + temperature in the
collapsed notification; optional alert when mining stops unexpectedly.

### F7 · Multi-coin architecture
The logo is coin-agnostic now. Define a `HashAlgorithm` interface (native
init/compute/target), keep kHeavyHash as the first implementation, add coin
selection to Settings and telemetry (`coin` field already exists). Pick the
second coin by mobile-CPU viability.

### F8 · Pool presets and earnings estimate
A pool picker (OverBuild Labs pool first) and an estimated KAS/day from the
60 s hashrate and network difficulty.

### F9 · Localization
Move hard-coded Compose strings into `res/values/strings.xml`; first extra
language by user demand.

### F10 · Opt-in crash and diagnostic report
Capture uncaught exceptions to a local file, show on the Logs screen with a
Copy button (like the thermal report). No network upload by default.

### F11 · Distribution
AAB build, Play Store listing (special-use FGS justification is already in
the manifest), and/or F-Droid with reproducible builds. Requires R2, R10.

### F12 · Signed release automation
GitHub Action on tag: build, sign (keystore from encrypted secret), compute
SHA-256, attach `overmobile-vX.Y.Z.apk` + `.sha256` to the release. Replaces
the manual steps in `docs/RELEASING.md`.

---

## Engineering

### E1 · Split `MainActivity.kt`
2,150 lines in one file. Extract Dashboard, Settings, Logs, About and the
OverManager card into separate composables/files with a `ViewModel`, without
behaviour changes.

### E2 · Expose service state as `StateFlow`
The UI polls the bound service every second and the service has
`@Volatile` fields read from many threads. A single immutable `MinerState`
`StateFlow` simplifies the UI, widgets (F5) and testing.

### E3 · Kotlin/AGP upgrade
Kotlin 1.9.22 / AGP 8.2.2 / Gradle 8.5 / compose-bom 2024.02. Upgrade
together with R2; re-check the Compose + R8 crash noted in `build.gradle.kts`.

---

## Done

- R15 (settings edits and the running session) plus R11 (thread-count
  clamp): Settings Save applies threads to the live session within thermal
  limits and reconnects for a new pool / wallet / worker, through the same
  `MiningService.applyMinerConfig` path as `set_config` / `set_threads`;
  every thread count is clamped to `min(cores, 8)`; the THREADS tile shows
  the live session — PR TBD, branch `claude/r15-settings-live-session`.
- Oct 21 2026 review bug batch: R5 (battery pause resumes while charging)
  plus unplugged-pause flapping, dashboard temperature colours, accepted-share
  count, pool authorization errors, Back button — branch
  `claude/project-thread-5ajqh1`.
- Oct 2026 audit fixes and in-app logo — branch `claude/audit-hardening-roadmap`
  (see [AUDIT-2026-10.md](AUDIT-2026-10.md)).
