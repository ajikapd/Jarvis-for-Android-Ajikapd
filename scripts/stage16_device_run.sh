#!/usr/bin/env bash
# Stage 16 device verification — Stage 14/15 command matrix + mcp=0 planner gate
set -euo pipefail
PKG=com.jarvis.assistant
RCV=$PKG/com.jarvis.app.DebugCommandReceiver
LOG=/mnt/D/Jarvis_for_android/md_files/.stage16_device.log
RESULT=/mnt/D/Jarvis_for_android/md_files/.stage16_results.tsv
: > "$LOG"
: > "$RESULT"

run_cmd() {
  local cmd="$1"
  adb logcat -c >/dev/null 2>&1 || true
  echo "=== CMD: $cmd ===" >> "$LOG"
  adb shell "am broadcast -a com.jarvis.assistant.DEBUG_COMMAND -n $RCV --es command \"$cmd\"" >> "$LOG" 2>&1
  local waited=0
  local got=""
  while (( waited < 120 )); do
    got=$(adb logcat -d -s Jarvis.VoiceService Jarvis.Debug Jarvis.Capability Jarvis.Planner 2>/dev/null || true)
    if echo "$got" | grep -q 'e2e first-audio'; then
      echo "$got" >> "$LOG"
      break
    fi
    if echo "$got" | grep -q 'NEEDS_CLARIFICATION'; then
      if echo "$got" | grep -q 'e2e first-audio\|status=NEEDS_CLARIFICATION'; then
        sleep 2
        got=$(adb logcat -d -s Jarvis.VoiceService Jarvis.Debug Jarvis.Capability 2>/dev/null || true)
        echo "$got" >> "$LOG"
        break
      fi
    fi
    if echo "$got" | grep -q 'command processing failed'; then
      echo "$got" >> "$LOG"
      break
    fi
    sleep 1
    ((waited++)) || true
  done
  local e2e brain slo status_line reply_status cap
  e2e=$(echo "$got" | grep 'e2e first-audio' | tail -1 | sed -n 's/.*e2e first-audio \([0-9]*\)ms.*/\1/p')
  brain=$(echo "$got" | grep 'e2e first-audio' | tail -1 | sed -n 's/.*brain=\([0-9]*\)ms.*/\1/p')
  slo=$(echo "$got" | grep 'e2e first-audio' | tail -1 | sed -n 's/.*slo1s=\([a-z]*\).*/\1/p')
  status_line=$(echo "$got" | grep -E 'status=PROCESSED|status=NEEDS_CLARIFICATION|AssistantResponse' | tail -1 || true)
  if echo "$status_line" | grep -q 'NEEDS_CLARIFICATION'; then
    reply_status=NEEDS_CLARIFICATION
  elif echo "$status_line" | grep -q 'PROCESSED'; then
    reply_status=PROCESSED
  else
    reply_status=UNKNOWN
  fi
  cap=$(echo "$got" | grep 'Jarvis.Capability' | tail -2 | tr '\n' ' ')
  local status=FAIL
  if [[ -n "$e2e" ]] && [[ "$reply_status" == "PROCESSED" ]]; then
    status=OK
  elif [[ "$reply_status" == "NEEDS_CLARIFICATION" ]] && [[ -n "$e2e" ]]; then
    status=PARTIAL
  elif [[ -n "$e2e" ]]; then
    status=OK
  elif echo "$got" | grep -q 'inject via VoiceService'; then
    status=PARTIAL
  fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$cmd" "$status" "$reply_status" "${e2e:-}" "${brain:-}" "${slo:-}" "${cap:-}" >> "$RESULT"
  echo "RESULT $cmd -> $status status=$reply_status e2e=${e2e:-na} brain=${brain:-na} slo=${slo:-na}" >> "$LOG"
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
  printf 'AUTONOMY:%s\t%s\t\t\t\t\t%s\n' "$action" "$status" "$line" >> "$RESULT"
}

COMMANDS=(
  "включи фонарик"
  "выключи фонарик"
  "громче"
  "тише"
  "погода"
  "который час"
  "сколько времени"
  "кто ты"
  "шутка"
  "привет"
  "что ты умеешь"
  "какой сегодня день"
  "открой настройки"
  "выключи музыку"
  "включи фонарик"
)

for c in "${COMMANDS[@]}"; do
  run_cmd "$c" || true
done

run_autonomy flashlight_on
run_autonomy volume_up

echo "DONE" >> "$LOG"
cat "$RESULT"
