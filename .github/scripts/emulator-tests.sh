#!/usr/bin/env bash
# Runs the instrumented UI tests on an already-booted emulator and always collects evidence:
# screenshots taken by the tests, a screen recording, redacted logcat, and a bug report excerpt.
# The android-emulator-runner action executes each `script:` line in its own shell, so this lives in a file.
set -u
OUT=emulator-artifacts
mkdir -p "$OUT"

adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell mkdir -p /sdcard/Pictures/clickarr
adb logcat -c || true

adb shell screenrecord --time-limit 180 --bit-rate 4000000 /sdcard/clickarr-run.mp4 &
REC=$!

./gradlew :app:connectedDebugAndroidTest --no-daemon
RESULT=$?

adb shell pkill -2 screenrecord || true
wait $REC 2>/dev/null || true
sleep 2

adb pull /sdcard/Pictures/clickarr "$OUT/screenshots" || true
adb pull /sdcard/clickarr-run.mp4 "$OUT/" || true
adb exec-out screencap -p > "$OUT/final-screen.png" || true
adb logcat -d -v time > "$OUT/logcat-full.txt" || true
grep -E "Clickarr/|AndroidRuntime|TestRunner|FATAL|net\.clickarr" "$OUT/logcat-full.txt" > "$OUT/logcat-clickarr.txt" || true
adb shell dumpsys activity activities 2>/dev/null | grep -E "mResumedActivity|topResumedActivity" > "$OUT/activities.txt" || true

echo "connectedDebugAndroidTest exit code: $RESULT"
exit $RESULT
