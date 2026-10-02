#!/usr/bin/env bash
# Installs an APK on the running emulator, launches the app and fails if it crashes.
#
# Catches what unit tests cannot: R8/ProGuard stripping a class the release build needs,
# APIs missing on the oldest supported Android version, manifest and resource mistakes, and
# crashes at startup or in the mesh service while there is no Bluetooth hardware.
#
# usage: smoke-test.sh <apk> [seconds to keep the app running]
set -u

APK="$1"
RUN_SECONDS="${2:-45}"
PKG="com.bluewhale.droid"
ACTIVITY="com.bluewhale.android.MainActivity"
OUT="smoke-logs"
mkdir -p "$OUT"

fail() {
  echo "::error::$1"
  adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
  adb logcat -d -b crash > "$OUT/crash.txt" 2>/dev/null || true
  exit 1
}

adb wait-for-device
echo "Android $(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))"

# -g grants every runtime permission, so the app gets past onboarding to the mesh service
adb install -r -g "$APK" || fail "install failed"
adb logcat -c
adb logcat -b crash -c || true

adb shell am start -W -n "$PKG/$ACTIVITY" || fail "could not start $ACTIVITY"

# Watch for the whole period: a crash in the mesh service or a background coroutine can come
# well after the first frame
for _ in $(seq 1 "$RUN_SECONDS"); do
  sleep 1
  if [ -z "$(adb shell pidof "$PKG" | tr -d '\r')" ]; then
    adb logcat -d -b crash | grep -A40 "Process: $PKG" || true
    fail "$PKG is no longer running"
  fi
done

adb logcat -d > "$OUT/logcat.txt"
adb logcat -d -b crash > "$OUT/crash.txt" || true

if grep -q "Process: $PKG" "$OUT/crash.txt" "$OUT/logcat.txt"; then
  grep -A40 "Process: $PKG" "$OUT/crash.txt" "$OUT/logcat.txt" | head -80
  fail "$PKG crashed"
fi

# Errors R8 causes when a class or member the app looks up at runtime was removed or renamed.
# They are often caught and logged instead of crashing, and break a feature silently.
PID_LINES="$OUT/app.txt"
PID="$(adb shell pidof "$PKG" | tr -d '\r')"
adb logcat -d --pid="$PID" > "$PID_LINES"
if grep -E "ClassNotFoundException|NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError|AbstractMethodError|ExceptionInInitializerError|UnsatisfiedLinkError" "$PID_LINES"; then
  fail "$PKG logged a missing class, member or native library"
fi

# Back to the launcher and in again: activity recreation and service rebinding
adb shell input keyevent KEYCODE_HOME
sleep 3
adb shell am start -W -n "$PKG/$ACTIVITY" || fail "could not restart $ACTIVITY"
sleep 10
[ -n "$(adb shell pidof "$PKG" | tr -d '\r')" ] || fail "$PKG died after returning from the background"
adb logcat -d -b crash | grep -q "Process: $PKG" && fail "$PKG crashed after returning from the background"

echo "$PKG ran for ${RUN_SECONDS}s and survived a background/foreground cycle"
