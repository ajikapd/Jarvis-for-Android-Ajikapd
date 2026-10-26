#!/usr/bin/env bash
# Stage 10.5 device verification — sequential DEBUG_COMMAND with logcat wait
set -euo pipefail
PKG=com.jarvis.assistant
RCV=$PKG/com.jarvis.app.DebugCommandReceiver
LOG=/tmp/stage10_5_device.log
RESULT=/tmp/stage10_5_results.tsv
: > "$LOG"
: > "$RESULT"

wait_for_e2e() {
  local label="$1"
  local timeout="${2:-90}"
  local i=0
  while (( i < timeout )); do
    if adb logcat -d -s Jarvis.VoiceService Jarvis.Debug Jarvis.Capability 2>/dev/null | grep -q 'e2e first-audio\|status=PROCESSED\|command processing failed'; then
      # need fresh line after our inject marker
      sleep 0.5
      return 0
    fi
    sleep 1
    ((i++)) || true
  done
  echo "TIMEOUT waiting e2e for: $label" >> "$LOG"
  return 1
}

run_cmd() {
  local cmd="$1"
  local marker="STAGE105_${RANDOM}"
  adb logcat -c >/dev/null 2>&1 || true
  echo "=== CMD: $cmd ===" >> "$LOG"
  adb shell "am broadcast -a com.jarvis.assistant.DEBUG_COMMAND -n $RCV --es command \"$cmd\"" >> "$LOG" 2>&1
  local waited=0
  local got=""
  while (( waited < 120 )); do
    got=$(adb logcat -d -s Jarvis.VoiceService Jarvis.Debug Jarvis.Capability 2>/dev/null || true)
    if echo "$got" | grep -q 'e2e first-audio'; then
      echo "$got" >> "$LOG"
      break
    fi
    if echo "$got" | grep -q 'command processing failed'; then
      echo "$got" >> "$LOG"
      break
    fi
    sleep 1
    ((waited++)) || true
  done
  local e2e brain slo proc cap reply fail
  e2e=$(echo "$got" | grep 'e2e first-audio' | tail -1 | sed -n 's/.*e2e first-audio \([0-9]*\)ms.*/\1/p')
  brain=$(echo "$got" | grep 'e2e first-audio' | tail -1 | sed -n 's/.*brain=\([0-9]*\)ms.*/\1/p')
  slo=$(echo "$got" | grep 'e2e first-audio' | tail -1 | sed -n 's/.*slo1s=\([a-z]*\).*/\1/p')
  proc=$(echo "$got" | grep -E 'status=PROCESSED|AssistantResponse' | tail -1 || true)
  cap=$(echo "$got" | grep 'Jarvis.Capability' | tail -3 | tr '\n' ' ')
  reply=$(echo "$got" | grep -E 'reply=|replyText' | tail -1 || true)
  fail=$(echo "$got" | grep 'command processing failed' | tail -1 || true)
  local status=FAIL
  if [[ -n "$e2e" ]] && echo "$got" | grep -q 'inject via VoiceService'; then
    status=OK
  elif [[ -n "$fail" ]]; then
    status=FAIL
  elif echo "$got" | grep -q 'inject via VoiceService'; then
    status=PARTIAL
  fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$cmd" "$status" "${e2e:-}" "${brain:-}" "${slo:-}" "${cap:-}" >> "$RESULT"
  echo "RESULT $cmd -> $status e2e=${e2e:-na} brain=${brain:-na} slo=${slo:-na}" >> "$LOG"
  sleep 1
}

run_autonomy() {
  local action="$1"
  adb logcat -c >/dev/null 2>&1 || true
  echo "=== AUTONOMY: $action live=false ===" >> "$LOG"
  adb shell am broadcast -a com.jarvis.assistant.DEBUG_AUTONOMY -n "$RCV" --es action "$action" --ez live false >> "$LOG" 2>&1
  local waited=0 got=""
  while (( waited < 30 )); do
    got=$(adb logcat -d -s Jarvis.Debug 2>/dev/null || true)
    if echo "$got" | grep -q 'autonomy dry-run'; then
      echo "$got" >> "$LOG"
      break
    fi
    sleep 1
    ((waited++)) || true
  done
  local line status
  line=$(echo "$got" | grep 'autonomy dry-run' | tail -1 || true)
  if echo "$line" | grep -q 'READY_TO_EXECUTE\|ALLOW'; then status=OK; else status=FAIL; fi
  printf 'AUTONOMY:%s\t%s\t\t\t\t%s\n' "$action" "$status" "$line" >> "$RESULT"
}

# Startup capture (caller may have already started app)
adb logcat -d -s Jarvis.AIProvider Jarvis.ChatLlm Jarvis.Capability Jarvis.VoiceService 2>/dev/null >> "$LOG" || true

COMMANDS=(
  "включи фонарик"
  "выключи фонарик"
  "сделай громче"
  "сделай тише"
  "какая погода"
  "который час"
  "сколько времени"
  "кто ты"
  "расскажи шутку"
  "привет"
  "что ты умеешь"
  "открой настройки"
  "поставь таймер на 1 минуту"
  "какая громкость"
  "громкость максимум"
  "сделай потише"
  "выключи музыку"
  "какая сегодня дата"
  "какой сегодня день"
)

for c in "${COMMANDS[@]}"; do
  run_cmd "$c" || true
done

run_autonomy flashlight_on
run_autonomy volume_up

echo "DONE" >> "$LOG"
cat "$RESULT"
