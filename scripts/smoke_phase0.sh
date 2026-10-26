#!/usr/bin/env bash
# Phase 0 smoke helper — ручной прогон с фиксацией в SMOKE_RESULTS.md
# Usage: ./scripts/smoke_phase0.sh
# Requires: adb device + installed debug APK + a11y/NLS enabled

set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${SMOKE_OUT:-$ROOT/SMOKE_RESULTS.md}"
TAGS="VoiceService:D JarvisSpeaker:D LlmBrain:D RuleBasedBrain:D MusicTool:D ActionCoordinator:D LauncherTool:D UiWaitTool:D *:S"

echo "== Jarvis Phase 0 smoke =="
echo "Results file: $OUT"
adb devices -l
DEVICE_COUNT="$(adb devices | awk 'NR>1 && $2=="device"{c++} END{print c+0}')"
if [[ "$DEVICE_COUNT" -lt 1 ]]; then
  echo "ERROR: no adb device. Connect phone and retry."
  exit 1
fi

mkdir -p "$(dirname "$OUT")"
{
  echo "# SMOKE_RESULTS — Phase 0"
  echo
  echo "Дата: $(date -Iseconds)"
  echo "Device: $(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
  echo "Build: $(adb shell getprop ro.build.display.id 2>/dev/null | tr -d '\r')"
  echo
  echo "Заполняй PASS/FAIL и цифры руками после каждой фразы."
  echo "Логи: \`adb logcat -s VoiceService JarvisSpeaker MusicTool\`"
  echo
  echo "## A. Latency (rules) — цель first-audio ≤ 1000 ms"
  echo
  echo "| # | Фраза | tts first-audio ms | e2e ms | slo1s | PASS/FAIL |"
  echo "|---|-------|--------------------:|-------:|:-----:|:---------:|"
  echo "| 1 | включи фонарик |  |  |  |  |"
  echo "| 2 | выключи фонарик |  |  |  |  |"
  echo "| 3 | сделай громче |  |  |  |  |"
  echo "| 4 | сделай тише |  |  |  |  |"
  echo "| 5 | поставь на паузу |  |  |  |  |"
  echo "| 6 | продолжай |  |  |  |  |"
  echo "| 7 | который час |  |  |  |  |"
  echo "| 8 | ярче |  |  |  |  |"
  echo "| 9 | темнее |  |  |  |  |"
  echo "|10 | поставь таймер на 1 минуту |  |  |  |  |"
  echo
  echo "Итого slo1s pass rate: __ / 10"
  echo
  echo "## B. Музыка без Kion (активная MediaSession Spotify/YTM/VK)"
  echo
  echo "| # | Фраза | Плеер | Результат | UI мигнул? | PASS/FAIL |"
  echo "|---|-------|-------|-----------|:----------:|:---------:|"
  echo "| 1 | поставь на паузу |  |  |  |  |"
  echo "| 2 | дальше / следующий |  |  |  |  |"
  echo "| 3 | включи [трек] |  |  |  |  |"
  echo "| 4 | что играет |  |  |  |  |"
  echo
  echo "## C. ReAct 2–3 шага (≥3 сценария)"
  echo
  echo "| # | Фраза | Шаги (tool ids) | PASS/FAIL |"
  echo "|---|-------|-----------------|:---------:|"
  echo "| 1 | открой настройки и включи Wi-Fi |  |  |"
  echo "| 2 | открой Telegram и нажми поиск |  |  |"
  echo "| 3 | открой часы и что на экране |  |  |"
  echo
  echo "## D. Telegram-like (5 прогонов подряд)"
  echo
  echo "Фраза: «открой Telegram, найди чат с … и напиши …»"
  echo
  echo "| Прогон | PASS/FAIL | Заметка |"
  echo "|-------:|:---------:|---------|"
  echo "| 1 |  |  |"
  echo "| 2 |  |  |"
  echo "| 3 |  |  |"
  echo "| 4 |  |  |"
  echo "| 5 |  |  |"
  echo
  echo "Success rate: __ / 5"
  echo
  echo "## E. Новые примитивы Phase 0"
  echo
  echo "| # | Проверка | PASS/FAIL |"
  echo "|---|----------|:---------:|"
  echo "| 1 | ui.wait / «подожди пока появится …» |  |"
  echo "| 2 | сверни приложение → Home (minimize) |  |"
  echo "| 3 | принудительно закрой X → confirm + settings |  |"
  echo
  echo "## Лог-захват (опционально)"
  echo
  echo "\`\`\`"
  echo "adb logcat -c"
  echo "adb logcat -v time $TAGS | tee /tmp/jarvis-smoke.log"
  echo "\`\`\`"
} > "$OUT"

echo "Wrote checklist → $OUT"
echo "Start logcat in another terminal, run voice phrases, fill PASS/FAIL."
echo "Watch for: 'tts first-audio …ms' and 'e2e …ms slo1s='"
