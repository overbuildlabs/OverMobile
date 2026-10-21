# CLAUDE.md — working on OverMobile

Read this first in every new session. It's the shared context for humans and
AI agents (Claude Code sessions, sub-agents) working in this repo.

OverMobile (formerly PoPMobile) is an Android Kaspa kHeavyHash miner by
OverBuild Labs. Kotlin + Jetpack Compose UI, native C miner via JNI, optional
telemetry/remote control by the OverManager desktop dashboard.

- Repo: `github.com/overbuildlabs/overmobile` (old `proofofprints/PoPMobile`
  URLs redirect there).
- Open work lives in [`docs/ROADMAP.md`](docs/ROADMAP.md) (ids like R1, F3);
  the latest security/bug audit is [`docs/AUDIT-2026-10.md`](docs/AUDIT-2026-10.md).
  Pick an item, do it on a branch, move it to *Done* in the same PR.
- Highest-priority open item: **R1** — OverManager commands are not
  authenticated (LAN attacker can redirect payouts). Needs OverManager changes.

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
  update/ApkVerifier.kt   hash + package + signer check before any install
tools/typecheck/          cloud-session Kotlin type-check (see below)
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
- **Cloud sessions can't run Gradle** (no Android SDK in the Claude Code
  cloud sandbox). What they *can* do:
  - `tools/typecheck/typecheck.sh` — compiles every Kotlin source with
    kotlinc against Android API jars from Maven (downloads ~250 MB into a
    cache on first run). Run it after any Kotlin change. It's a type-check,
    not a build: no resources, manifest merge, R8 or native code.
  - Native code: `gcc -fsyntax-only` / host test harnesses with a stub
    `android/log.h` (the engine compiles on Linux); ThreadSanitizer is
    available for concurrency changes.
  - XML: `python3 -c "import xml.dom.minidom as m; m.parse('file.xml')"`.
  The maintainer builds and runs on a device in Android Studio (test device:
  TCL A600DL, Android 11) before merging. Say in the PR what was and wasn't
  verified; never claim a build or device test you didn't run.
- No unit tests yet — roadmap R4.

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
single session verifying and applying fixes (that's how the Oct 2026 audit
was run). Give each agent the invariants table above and tell it whether it
may edit. Two agents shouldn't edit the same file at once — `MainActivity.kt`
and `MiningService.kt` are touched by most items.

## Threading model (read before touching the service)

- Native: N worker threads + 1 hashrate sampler thread. `mining_start/stop`
  are serialised by `control_mutex`; job/extranonce state is under
  `job_mutex`; the share callback holds a read lock on the JNI global ref.
- `onShareFound` runs on a native worker thread — never block it; it posts
  `submitShare` to `Dispatchers.IO`.
- `StratumClient`: one reader coroutine per connection; `connectionGen`
  ignores events from old connections; `disconnect()` is intentional and does
  not call `onDisconnected`.
- `MiningService`: `startMining`/`stopMining` from the main thread or the
  reporter's IO thread; `statsUpdateLoop` owns throttle/pause transitions
  after the first job starts the engine (`engineStartedOnce`).
- `PoPManagerReporter`: report loop on IO; `sendReport` is serialised by
  `reportLock`.
