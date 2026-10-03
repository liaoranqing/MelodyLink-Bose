#!/usr/bin/env bash
# Local syntax gate for MelodyLink-Bose.
#
# Why: two CI failures in a row shipped broken Java (a misspelled constant and a
# boolean compared to null). This compiles every app source with javac against
# the platform jar plus the Gradle dependency cache, so those class of mistakes
# surface in seconds instead of a full CI round-trip.
#
# Usage:  bash tools/javac-check.sh
# Exit:   0 = no errors, non-zero = error count (see output).

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVAC="${JAVAC:-C:/Users/liaoran/.workbuddy/binaries/jdk/jdk-17.0.2/bin/javac.exe}"
ANDROID_JAR="${ANDROID_JAR:-C:/Users/liaoran/.workbuddy/binaries/android-sdk/platforms/android-36/android.jar}"
GRADLE_CACHE="${GRADLE_CACHE:-$HOME/.gradle/caches/modules-2/files-2.1}"
OUT="${OUT:-$(mktemp -d)}"

if [ ! -x "$JAVAC" ] && ! command -v "$JAVAC" >/dev/null 2>&1; then
  echo "javac not found: $JAVAC (override with JAVAC=...)" >&2
  exit 2
fi
if [ ! -f "$ANDROID_JAR" ]; then
  echo "android.jar not found: $ANDROID_JAR (override with ANDROID_JAR=...)" >&2
  exit 2
fi

# The compileSdk in app/build.gradle.kts must match the jar we check against.
# A mismatch silently hides errors: android-36 still declares
# BluetoothDevice.getConnectionState()/STATE_CONNECTED while the android-37.0 jar
# the CI actually uses does not, so a local pass said nothing (0.5.2 CI failure).
COMPILE_SDK=$(grep -oE 'compileSdk\s*=\s*[0-9]+' app/build.gradle.kts | grep -oE '[0-9]+' | head -1)
JAR_SDK=$(echo "$ANDROID_JAR" | sed -E 's#.*android-([0-9]+).*#\1#')
if [ -n "$COMPILE_SDK" ] && [ "$COMPILE_SDK" != "$JAR_SDK" ]; then
  echo "SDK MISMATCH: build declares compileSdk=$COMPILE_SDK but ANDROID_JAR is android-$JAR_SDK"
  echo "  a local pass would be meaningless; point ANDROID_JAR at the matching platform"
  exit 3
fi
echo "compileSdk=$COMPILE_SDK, checking against android-$JAR_SDK"

CP="$ANDROID_JAR"
# android.jar alone lacks androidx/material; add every cached dependency jar.
while IFS= read -r jar; do
  CP="$CP;$jar"
done < <(find "$GRADLE_CACHE" -name "*.jar" 2>/dev/null | grep -viE "sources|javadoc")

SRC_LIST="$OUT/sources.txt"
find "$ROOT/app/src/main/java" -name "*.java" > "$SRC_LIST"
echo "checking $(wc -l < "$SRC_LIST") source files with $(echo "$CP" | tr ';' '\n' | grep -c jar) jars"

LOG="$OUT/javac.log"
"$JAVAC" -proc:none -nowarn -d "$OUT/classes" -cp "$CP" "@$SRC_LIST" > "$LOG" 2>&1

ERRORS=$(grep -c "error:" "$LOG" || true)
if [ "$ERRORS" -ne 0 ]; then
  echo "FAILED: $ERRORS error(s)"
  grep -E "error:" "$LOG" | head -20
  echo "full log: $LOG"
  exit 1
fi
echo "OK: no compile errors"
