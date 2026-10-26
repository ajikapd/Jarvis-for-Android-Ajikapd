#!/usr/bin/env bash
set -euo pipefail
PKG=com.jarvis.assistant
APK=/mnt/D/Jarvis_for_android/app/build/outputs/apk/debug/app-debug.apk
STARTUP_LOG=/mnt/D/Jarvis_for_android/md_files/.stage19_27_startup.log
: > "$STARTUP_LOG"

echo "=== install ===" | tee -a "$STARTUP_LOG"
adb install -r "$APK" 2>&1 | tee -a "$STARTUP_LOG"
ls -la "$APK" | tee -a "$STARTUP_LOG"

adb shell am force-stop "$PKG" 2>&1 | tee -a "$STARTUP_LOG"
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO 2>&1 | tee -a "$STARTUP_LOG"

echo "=== dashboard ===" | tee -a "$STARTUP_LOG"
adb shell am start -n "$PKG/com.jarvis.app.DashboardActivity" 2>&1 | tee -a "$STARTUP_LOG"
sleep 2

RCV=$PKG/com.jarvis.app.DebugCommandReceiver
adb logcat -c >/dev/null 2>&1 || true
echo "=== DEBUG_VOICE start ===" | tee -a "$STARTUP_LOG"
adb shell am broadcast -a com.jarvis.assistant.DEBUG_VOICE -n "$RCV" --es action start 2>&1 | tee -a "$STARTUP_LOG"
echo "waiting 16s warm-up..." | tee -a "$STARTUP_LOG"
sleep 16

adb logcat -d -s Jarvis.Planner Jarvis.AIProvider Jarvis.VoiceService 2>&1 | tee -a "$STARTUP_LOG"

CLOUD=$(grep -o 'cloudKey=[a-z]*' "$STARTUP_LOG" | tail -1 || true)
echo "CLOUD_MARKER=$CLOUD" | tee -a "$STARTUP_LOG"
