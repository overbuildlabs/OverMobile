# tools/typecheck

Type-checks every Kotlin file under `app/src/main/java` (Compose UI included)
on a plain JVM, with no Android SDK and no Android Gradle plugin. It exists for
environments where `./gradlew` can't run, such as cloud Claude Code sessions,
so that Kotlin edits can still be checked before they are committed.

```sh
tools/typecheck/typecheck.sh                # full check, Compose included
tools/typecheck/typecheck.sh --no-compose   # skip the Compose UI files
tools/typecheck/typecheck.sh --warnings     # also print compiler warnings
tools/typecheck/typecheck.sh --offline      # use only jars already cached
```

The exit status is 0 when there are no errors and 1 when there are compile
errors, which are printed as `file:line:col: error: ...`. It is 2 when setup
fails, for example when a download fails or a stub doesn't compile. A warm run
takes about 35 s.

Requirements: `java` (17 or newer), `curl`, `unzip`, and `bash`.

## How it works

1. **Dependencies.** The script downloads jars from Maven Central into
   `~/.cache/overmobile-typecheck`, or into `$OVERMOBILE_TYPECHECK_CACHE` if
   that is set. Downloads run one at a time with retries, and files already in
   the cache are skipped. Mirrors are tried in order: Google's Central mirror,
   then `repo1.maven.org` (which rate-limits by IP), then
   `plugins.gradle.org/m2`. You can override the list with `MAVEN_REPOS`. The
   first run downloads about 250 MB, most of it `android-all`.
   - **Compiler:** `kotlin-compiler-embeddable` 1.9.22, the same Kotlin
     version the app uses.
   - **Android framework:** `org.robolectric:android-all:14-robolectric-10818077`,
     which is API 34.
   - **Compose:** Google Maven (`dl.google.com`) is blocked from cloud
     sessions. The script therefore uses the **JetBrains Compose Multiplatform
     *desktop* jars at 1.6.0** from Maven Central instead. They expose the same
     `androidx.compose.*` API as Jetpack Compose 1.6 and material3 1.2, which
     roughly matches compose-bom 2024.02.02. The Compose compiler plugin is
     `org.jetbrains.compose.compiler:compiler:1.5.8`, which is JetBrains'
     republish of `androidx.compose.compiler:compiler:1.5.8`. It is loaded
     with `-Xplugin`, so `@Composable` misuse is reported as well.
   - **Other libraries:** coroutines, gson, zxing core and
     zxing-android-embedded, all real jars.
2. **Stubs** (`stubs/`). These replace Google-Maven-only APIs and Android-only
   Compose APIs. They are compiled first, as their own classpath entry, because
   real jars refer to them (zxing's `ScanContract` extends the stubbed
   `ActivityResultContract`).
   - `common/`: `BuildConfig`, `androidx.core` (`ContextCompat`,
     `FileProvider`), the `androidx.activity.result` API, and the
     `androidx.lifecycle` core types.
   - `compose/`: `ComponentActivity`, `setContent`, `LocalContext`,
     `LocalLifecycleOwner`, and `painterResource(id)` and its siblings.
   - `no-compose/`: used only with `--no-compose`. Provides a
     `mutableStateListOf` shim for `LogManager` and a placeholder
     `MainActivity` for `MiningService`.
3. **R.** The script generates `R` on each run by scanning
   `app/src/main/res`. It reads file names per resource type, `values/*.xml`
   entries, and `@+id/` declarations, so new resources work with no extra
   steps.
4. **Compile.** It compiles all app sources with `-jvm-target 17` and prints
   the errors.

## Limitations

- **This is a type-check, not a build.** It does no resource or manifest
  processing, no AAPT, no R8, no native/CMake build, and no tests. It doesn't
  catch broken XML, manifest entries, missing resources, or runtime crashes.
  Only Gradle catches those.
- **Stubs can hide errors.** Errors in calls to stubbed APIs are checked only
  against the stub's signatures. Some members are missing from the stubs on
  purpose. If the app starts using `lifecycleScope`, `ViewModel`,
  `ActivityCompat`, and similar APIs, you'll get `Unresolved reference` until
  you add them to `stubs/` with the real library signature. That error is a
  stub gap, not an app bug.
- **Compose desktop isn't Compose Android.** Version skew is possible, so an
  API that exists in one may be missing or deprecated in the other. Android-only
  Compose APIs, such as anything in `ui-android` or `activity-compose`, need a
  stub.
- **JDK, not Android, for `java.*`.** The JDK on `PATH` provides the `java.*`
  classes. Warnings like "`URL(String)` is deprecated" come from JDK 21 and
  don't appear on Android.
- **Main source set only.** Test and androidTest sources aren't compiled.
- If the Compose jars can't be downloaded, run with `--no-compose`. That
  skips `MainActivity`, `SplashActivity`, `Theme`, and `UpdateDialog`.
