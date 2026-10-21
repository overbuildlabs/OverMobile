#!/usr/bin/env bash
# Type-check every Kotlin source under app/src/main/java on a plain JVM,
# without the Android SDK or the Android Gradle plugin. See README.md.
#
# Usage: tools/typecheck/typecheck.sh [--no-compose] [--warnings] [--offline]
#
#   --no-compose  skip the Compose UI files (and the Compose jars/compiler
#                 plugin); use if the Compose deps can't be downloaded.
#   --warnings    also print compiler warnings (default: errors only).
#   --offline     don't download anything; fail if a jar is missing.
#
# Env:
#   OVERMOBILE_TYPECHECK_CACHE  jar cache dir (default ~/.cache/overmobile-typecheck)
#   MAVEN_REPOS                 space-separated Maven Central mirrors, tried in
#                               order (default: Google's Central mirror,
#                               repo1.maven.org, plugins.gradle.org/m2)
#   TYPECHECK_JAVA_OPTS         JVM options for the compiler (default -Xmx3g)
#
# Exit status: 0 = no errors, 1 = compile errors, 2 = setup failure.
set -euo pipefail

COMPOSE=1
SHOW_WARNINGS=0
OFFLINE=0
for arg in "$@"; do
  case "$arg" in
    --no-compose) COMPOSE=0 ;;
    --warnings) SHOW_WARNINGS=1 ;;
    --offline) OFFLINE=1 ;;
    -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$ROOT/app/src/main/java"
RES="$ROOT/app/src/main/res"
APP_PKG="com.proofofprints.popmobile"
CACHE="${OVERMOBILE_TYPECHECK_CACHE:-$HOME/.cache/overmobile-typecheck}"
MAVEN_REPOS="${MAVEN_REPOS:-https://maven-central.storage-download.googleapis.com/maven2 https://repo1.maven.org/maven2 https://plugins.gradle.org/m2}"
JAVA_OPTS="${TYPECHECK_JAVA_OPTS:--Xmx3g}"

command -v java >/dev/null || { echo "typecheck: java not found on PATH" >&2; exit 2; }
command -v curl >/dev/null || { echo "typecheck: curl not found on PATH" >&2; exit 2; }
command -v unzip >/dev/null || { echo "typecheck: unzip not found on PATH" >&2; exit 2; }

KOTLIN=1.9.22
# JetBrains Compose Multiplatform desktop artifacts, republished on Maven
# Central. 1.6.0 tracks Jetpack Compose 1.6.x / material3 1.2.x, i.e. the
# app's compose-bom 2024.02.02. Google Maven (where the real Android Compose
# .aars live) is not reachable from cloud sessions.
CMP=1.6.0
# JetBrains' republish of androidx.compose.compiler:compiler 1.5.8 (the
# embeddable-compiler flavour, matching Kotlin 1.9.22 and the app's
# composeOptions.kotlinCompilerExtensionVersion).
COMPOSE_COMPILER=1.5.8

# ---------------------------------------------------------------------------
# Dependencies: "<group>:<artifact>:<version>[:aar]". A jar is cached as
# <artifact>-<version>.jar; for an .aar its classes.jar is extracted to that
# name.
TOOL_DEPS="
org.jetbrains.kotlin:kotlin-compiler-embeddable:$KOTLIN
org.jetbrains.kotlin:kotlin-stdlib:$KOTLIN
org.jetbrains.kotlin:kotlin-script-runtime:$KOTLIN
org.jetbrains.kotlin:kotlin-reflect:1.6.10
org.jetbrains.kotlin:kotlin-daemon-embeddable:$KOTLIN
org.jetbrains.intellij.deps:trove4j:1.0.20200330
org.jetbrains:annotations:13.0
"
BASE_DEPS="
org.robolectric:android-all:14-robolectric-10818077
org.jetbrains.kotlin:kotlin-stdlib:$KOTLIN
org.jetbrains:annotations:13.0
org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.7.3
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3
com.google.code.gson:gson:2.10.1
com.google.zxing:core:3.5.3
com.journeyapps:zxing-android-embedded:4.3.0:aar
"
COMPOSE_DEPS="
org.jetbrains.compose.compiler:compiler:$COMPOSE_COMPILER
org.jetbrains.compose.runtime:runtime-desktop:$CMP
org.jetbrains.compose.runtime:runtime-saveable-desktop:$CMP
org.jetbrains.compose.ui:ui-desktop:$CMP
org.jetbrains.compose.ui:ui-geometry-desktop:$CMP
org.jetbrains.compose.ui:ui-graphics-desktop:$CMP
org.jetbrains.compose.ui:ui-text-desktop:$CMP
org.jetbrains.compose.ui:ui-unit-desktop:$CMP
org.jetbrains.compose.ui:ui-util-desktop:$CMP
org.jetbrains.compose.foundation:foundation-desktop:$CMP
org.jetbrains.compose.foundation:foundation-layout-desktop:$CMP
org.jetbrains.compose.animation:animation-desktop:$CMP
org.jetbrains.compose.animation:animation-core-desktop:$CMP
org.jetbrains.compose.material3:material3-desktop:$CMP
org.jetbrains.compose.material:material-icons-core-desktop:$CMP
org.jetbrains.compose.material:material-ripple-desktop:$CMP
org.jetbrains.skiko:skiko-awt:0.7.93
org.jetbrains.kotlinx:atomicfu-jvm:0.23.2
org.jetbrains.kotlinx:kotlinx-datetime-jvm:0.5.0
"

mkdir -p "$CACHE"

# Downloads <repo-relative path> to a file, trying each mirror, retrying with
# backoff. Run sequentially: the agent proxy answers HTTP 429 when too many
# requests are in flight, and repo1.maven.org rate-limits by IP (sometimes
# with a 200 + text body, hence the zip magic check).
fetch() {
  local path="$1" out="$2" tmp="$2.part" code i repo
  for i in 1 2 3 4; do
    for repo in $MAVEN_REPOS; do
      code=$(curl -sS -L -o "$tmp" -w '%{http_code}' --connect-timeout 20 "$repo/$path" 2>/dev/null) || code=000
      if [ "$code" = 200 ] && [ "$(head -c 2 "$tmp" 2>/dev/null)" = PK ]; then
        mv -f "$tmp" "$out"; return 0
      fi
      rm -f "$tmp"
    done
    sleep $((i * 3))
  done
  return 1
}

# Echoes the cached jar path for a coordinate, downloading it if needed.
resolve() {
  local coord="$1" g a v ext path jar
  IFS=: read -r g a v ext <<EOF
$coord
EOF
  ext="${ext:-jar}"
  jar="$CACHE/$a-$v.jar"
  if [ -s "$jar" ]; then echo "$jar"; return 0; fi
  if [ "$OFFLINE" = 1 ]; then echo "typecheck: missing $jar (offline)" >&2; return 1; fi
  path="$(echo "$g" | tr . /)/$a/$v/$a-$v.$ext"
  echo "typecheck: downloading $g:$a:$v ($ext)" >&2
  if [ "$ext" = aar ]; then
    fetch "$path" "$CACHE/$a-$v.aar" || { echo "typecheck: failed to download $path" >&2; return 1; }
    rm -rf "$CACHE/.x" && mkdir -p "$CACHE/.x"
    unzip -q -o "$CACHE/$a-$v.aar" classes.jar -d "$CACHE/.x"
    mv -f "$CACHE/.x/classes.jar" "$jar"
    rm -rf "$CACHE/.x" "$CACHE/$a-$v.aar"
  else
    fetch "$path" "$jar" || { echo "typecheck: failed to download $path" >&2; return 1; }
  fi
  echo "$jar"
}

# Resolves a list of coordinates into a ':'-joined classpath (echoed).
classpath_of() {
  local list="$1" cp="" c j
  for c in $list; do
    j="$(resolve "$c")" || return 1
    cp="${cp:+$cp:}$j"
  done
  echo "$cp"
}

TOOL_CP="$(classpath_of "$TOOL_DEPS")" || exit 2
LIB_CP="$(classpath_of "$BASE_DEPS")" || exit 2
PLUGIN_ARGS=()
if [ "$COMPOSE" = 1 ]; then
  if ! COMPOSE_CP="$(classpath_of "$COMPOSE_DEPS")"; then
    echo "typecheck: Compose dependencies unavailable; re-run with --no-compose" >&2
    exit 2
  fi
  PLUGIN_JAR="${COMPOSE_CP%%:*}"     # first entry is the compiler plugin
  LIB_CP="$LIB_CP:${COMPOSE_CP#*:}"
  PLUGIN_ARGS=("-Xplugin=$PLUGIN_JAR")
fi

# ---------------------------------------------------------------------------
# Work dir: generated R stub + class output.
WORK="$(mktemp -d "${TMPDIR:-/tmp}/overmobile-typecheck.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# Generate R from app/src/main/res so new resources never break the check.
gen_r() {
  local out="$1" dir type f name kind
  local tmp="$WORK/r-entries"
  : > "$tmp"
  for dir in "$RES"/*/; do
    [ -d "$dir" ] || continue
    type="$(basename "$dir")"; type="${type%%-*}"
    if [ "$type" = values ]; then
      for f in "$dir"*.xml; do
        [ -f "$f" ] || continue
        # <string name="x">, <color name=..>, <style name="A.B"> -> style A_B, ...
        grep -oE '<(string|color|dimen|bool|integer|style|plurals|string-array|integer-array|array|attr|item type="[a-z]+")[^>]* name="[^"]+"' "$f" 2>/dev/null |
          sed -E 's/^<item type="([a-z]+)".* name="([^"]+)"/\1 \2/;
                  s/^<(string-array|integer-array)[^>]* name="([^"]+)"/array \2/;
                  s/^<([a-z]+)[^>]* name="([^"]+)"/\1 \2/' >> "$tmp" || true
      done
    else
      for f in "$dir"*; do
        [ -f "$f" ] || continue
        name="$(basename "$f")"; name="${name%%.*}"
        echo "$type $name" >> "$tmp"
      done
    fi
  done
  # android:id="@+id/foo" anywhere in res XML.
  grep -rhoE '@\+id/[A-Za-z0-9_]+' "$RES" 2>/dev/null | sed 's#@+id/#id #' >> "$tmp" || true

  {
    echo "// Generated by tools/typecheck/typecheck.sh from app/src/main/res. Values are dummies."
    echo "package $APP_PKG"
    echo
    echo "object R {"
    local i=1
    for kind in $(awk '{print $1}' "$tmp" | sort -u); do
      echo "    object $kind {"
      for name in $(awk -v k="$kind" '$1==k {print $2}' "$tmp" | tr '.' '_' | sort -u); do
        echo "        const val \`$name\`: Int = $i"
        i=$((i + 1))
      done
      echo "    }"
    done
    echo "}"
  } > "$out"
}
gen_r "$WORK/R.kt"

# ---------------------------------------------------------------------------
# Sources.
SOURCES=()
SKIPPED=()
while IFS= read -r f; do
  if [ "$COMPOSE" = 0 ] && grep -qE '^import androidx\.(compose\.(ui|foundation|material3?|animation)|activity\.compose|activity\.ComponentActivity)|@Composable' "$f"; then
    SKIPPED+=("${f#"$ROOT"/}")
    continue
  fi
  SOURCES+=("$f")
done < <(find "$SRC" -name '*.kt' | sort)
SOURCES+=("$WORK/R.kt")

STUB_DIRS=("$HERE/stubs/common")
if [ "$COMPOSE" = 1 ]; then STUB_DIRS+=("$HERE/stubs/compose"); else STUB_DIRS+=("$HERE/stubs/no-compose"); fi
STUBS=()
while IFS= read -r f; do STUBS+=("$f"); done < <(find "${STUB_DIRS[@]}" -name '*.kt' | sort)

if [ "${#SKIPPED[@]}" -gt 0 ]; then
  echo "typecheck: --no-compose, skipping: ${SKIPPED[*]}" >&2
fi

# kotlinc <out-dir> <classpath> <sources...>; output goes to $WORK/log.
# -Xsuppress-version-warnings keeps the jvm-target chatter out.
kotlinc() {
  local out="$1" cp="$2"; shift 2
  # shellcheck disable=SC2086
  java $JAVA_OPTS -cp "$TOOL_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 17 \
    -Xsuppress-version-warnings \
    ${PLUGIN_ARGS[@]+"${PLUGIN_ARGS[@]}"} \
    -cp "$cp" \
    -d "$out" \
    "$@" >> "$WORK/log" 2>&1
}

# Stage 1: compile the stubs into their own output dir. They must be real
# classes on the classpath (not sources next to the app code) because real
# library jars refer to them, e.g. zxing's ScanContract extends the stubbed
# androidx ActivityResultContract, and binary supertypes can't resolve to
# source files.
: > "$WORK/log"
set +e
kotlinc "$WORK/stubs-out" "$LIB_CP" "${STUBS[@]}"
STATUS=$?
set -e
if [ "$STATUS" -ne 0 ]; then
  sed -e "s#$ROOT/##g" "$WORK/log" >&2
  echo "typecheck: the stubs in tools/typecheck/stubs failed to compile (setup problem, not an app error)" >&2
  exit 2
fi

# Stage 2: the app.
echo "typecheck: compiling ${#SOURCES[@]} app files + ${#STUBS[@]} stubs (compose=$COMPOSE)..." >&2
: > "$WORK/log"
set +e
kotlinc "$WORK/out" "$WORK/stubs-out:$LIB_CP" "${SOURCES[@]}"
STATUS=$?
set -e

# Paths relative to the repo; errors only unless --warnings.
sed -e "s#$ROOT/##g" -e "s#$WORK/#<generated>/#g" "$WORK/log" > "$WORK/log.rel"
if [ "$SHOW_WARNINGS" = 1 ]; then
  cat "$WORK/log.rel"
else
  # Print each error together with its source/caret lines.
  awk '/^[^ ].*: (warning|info): /{p=0} /^[^ ].*: error: /{p=1} /^error: /{p=1} p' "$WORK/log.rel"
fi

ERRORS=$(grep -cE '(^|: )error: ' "$WORK/log.rel" || true)
if [ "$STATUS" -ne 0 ]; then
  echo "typecheck: FAILED ($ERRORS error(s), compiler exit $STATUS)" >&2
  exit 1
fi
echo "typecheck: OK, no errors" >&2
