# CLAUDE.md — working on OverMobile

Read this first in every new session. It's the shared context for humans and
AI agents (Claude Code sessions, sub-agents) working in this repo.

OverMobile (formerly PoPMobile) is an Android Kaspa kHeavyHash miner by
OverBuild Labs. Kotlin + Jetpack Compose UI, native C miner via JNI, optional
telemetry/remote control by the OverManager desktop dashboard.

- Repo: `github.com/overbuildlabs/overmobile` (old `proofofprints/PoPMobile`
  URLs redirect there).
- Open work lives in [`docs/ROADMAP.md`](docs/ROADMAP.md); the latest
  security/bug audit is [`docs/AUDIT-2026-10.md`](docs/AUDIT-2026-10.md).
  Pick a task from the roadmap, do it on a branch, tick it off in the same PR.

## Invariants — never change these without an explicit decision

These keep existing installs upgrading in place and the native code bound.
Changing any one silently breaks users.

| Thing | Value | Why |
|---|---|---|
| `applicationId` | `com.proofofprints.kasminer` | Android install identity. Changing it = a different app; users lose all settings. |
| `namespace` / Kotlin package | `com.proofofprints.popmobile` | JNI symbols in `app/src/main/cpp/jni_bridge.cpp` are named `Java_com_proofofprints_popmobile_mining_MiningEngine_*`. Rename one without the other and the app crashes on first native call. |
| Signing key | `popmobile-release.keystore` (outside repo, `keystore.properties` gitignored) | Different key = updates refuse to install. Never regenerate. |
| SharedPreferences files | `kas_miner` (miner config), `popmanager` (pairing, API key, device id), `update_checker`, mining prefs | On-disk names; renaming wipes wallet/pool/pairing. |
| QR pairing type | `popmanager-register` | Wire format OverManager emits. Accept new names *in addition*, never instead. |
| Telemetry JSON field names | see `PoPManagerReporter.sendReport()` | OverManager parses them. |

Internal identifiers that still say `KASMiner` / `PoPManager` (class names,
theme style, log tags) are intentionally left; renaming them is pure churn.
User-visible text must say OverMobile / OverBuild Labs / OverManager.

## Layout

```
app/src/main/
  AndroidManifest.xml
  cpp/                      native miner (C), JNI bridge, CMakeLists.txt
    mining_engine.c         worker threads, job/target state, share callback
    kheavyhash.c, keccak.c  hash implementation
    jni_bridge.cpp          JNI entry points (names bound to the namespace!)
  java/com/proofofprints/popmobile/
    KASMinerApp.kt          Application
    LogManager.kt           in-app log buffer shown on the Logs screen
    mining/MiningEngine.kt  Kotlin facade over the JNI functions
    stratum/StratumClient.kt  stratum TCP client (subscribe/authorize/notify/submit)
    service/MiningService.kt  foreground service: owns engine, pool, poller, reporter
    service/ThermalMonitor.kt thermal zones + battery → throttle/pause decisions
    service/MiningPreferences.kt  thermal/battery thresholds
    service/PoolMigration.kt  rewrites saved legacy pool host on launch
    popmanager/PoPManagerReporter.kt  OverManager pairing, telemetry, remote commands
    ui/MainActivity.kt      the whole Compose UI (dashboard, settings, logs, about)
    ui/SplashActivity.kt, QrScannerActivity.kt, Theme.kt, FieldValidators.kt
    update/                 in-app updater (GitHub Releases → DownloadManager → installer)
  res/drawable/ic_overmobile_mark.xml   in-app logo (vector of docs/brand/overmobile-mark.svg)
  res/drawable/ic_stat_overmobile.xml   notification icon
docs/
  brand/        logo sources (SVG is the source of truth)
  screenshots/  OverMobile_*.png — the OverBuild Labs website hot-links these
                exact filenames; overwrite, never rename
  RELEASING.md  release checklist
  ROADMAP.md    prioritized backlog (bugs, hardening, features)
  AUDIT-*.md    point-in-time review findings
```

## Building and verifying

- `./gradlew assembleDebug` (Windows: `gradlew assembleDebug`). AGP 8.2.2,
  Kotlin 1.9.22, Gradle 8.5, compileSdk/targetSdk 34, minSdk 26, NDK via CMake.
- **Cloud sessions can't build**: the Android Gradle plugin and SDK aren't
  available offline in the Claude Code cloud sandbox. Changes made there are
  reviewed by reading, then built and run by the maintainer in Android Studio
  (test device: TCL A600DL, Android 11). Say so in the PR; don't claim a build
  passed.
- There are no unit tests yet (see roadmap). Pure-Kotlin helpers
  (`VersionCompare`, `FieldValidators`, `PoolMigration`, stratum parsing) are
  the first candidates.

## Conventions

- Branch per task (`claude/<topic>` for agent work), small focused commits,
  PR into `main`. Don't push to `main` directly.
- Commit messages: imperative subject, body explains *why*.
- Update `CHANGELOG.md` (Keep a Changelog format) in the PR that changes
  behaviour. Bump `versionCode`/`versionName` only in the release PR.
- Match surrounding code style; comments explain non-obvious *why*.
- Colors: emerald `#10B981` primary/connected, Kaspa teal `#70C7BA`
  readouts, amber `#FBBF24` warnings, red `#FF4444` errors, surfaces
  `#0C0C0F` / `#16161B`. Values in monospace, labels in sans-serif.
- Releases: follow `docs/RELEASING.md`. Tags can only be pushed from the
  maintainer's machine (the cloud git proxy refuses tag pushes).

## Using sub-agents

Good fan-out units for parallel agents, each on its own branch:
one roadmap item per agent; or one review dimension per agent (security,
Kotlin concurrency, native/JNI) reporting findings without editing, with a
single session applying fixes. Give each agent the invariants table above.
