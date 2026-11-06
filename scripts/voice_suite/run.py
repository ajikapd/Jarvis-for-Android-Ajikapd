#!/usr/bin/env python3
"""Прогон голосовых команд на телефоне через настоящий путь VoiceService (без микрофона).

Каждая команда проверяется по маршруту, тексту ответа и реальному состоянию устройства:
ассистент может сказать «готово» и ничего не сделать.

  python3 scripts/voice_suite/run.py                 весь набор
  python3 scripts/voice_suite/run.py --only volume   только группа
  python3 scripts/voice_suite/run.py --json out.json сохранить результаты

Опасные действия (звонок, SMS, Telegram) доводятся только до подтверждения и отменяются.
"""
import argparse, json, os, re, subprocess, sys, threading, time

RC = "com.jarvis.assistant/com.jarvis.app.DebugCommandReceiver"
TAGS = ["Jarvis.VoiceService:V", "Jarvis.Trace:I", "Jarvis.Speaker:D", "Jarvis.Debug:D", "AndroidRuntime:E", "*:S"]
DONE_RE = re.compile(r"(stream-chat|brain) (\d+)ms status=(\w+)(?: route=(\w+))?.*?reply=\"(.*)\"?$")

lines, lock = [], threading.Lock()
reader = None


def adb(cmd, timeout=15):
    try:
        return subprocess.run(["adb", "shell", cmd], capture_output=True, text=True, timeout=timeout).stdout.strip()
    except subprocess.TimeoutExpired:
        return ""


def logcat():
    global reader
    reader = subprocess.Popen(["adb", "logcat", "-T", "1", "-v", "time"] + TAGS, stdout=subprocess.PIPE,
                              stderr=subprocess.DEVNULL, text=True, errors="ignore")
    for line in reader.stdout:
        with lock:
            lines.append(line.rstrip())


def send(text):
    adb(f'am broadcast -a com.jarvis.assistant.DEBUG_COMMAND -n {RC} --es command "{text}"')


def wait_turn(start, timeout):
    t0 = time.time()
    done = None
    while time.time() - t0 < timeout:
        with lock:
            chunk = lines[start:]
        for l in chunk:
            m = DONE_RE.search(l)
            if m:
                done = m
                break
        if done:
            break
        time.sleep(0.1)
    return done, time.time() - t0


def speech_end(start, timeout=25):
    """Ждём конец озвучки: следующая команда не должна попасть в паузу wake."""
    t0 = time.time()
    while time.time() - t0 < timeout:
        with lock:
            chunk = lines[start:]
        if any("tts done" in l for l in chunk):
            return
        time.sleep(0.2)


def probe(name):
    if name == "volume":
        m = re.search(r"volume is (\d+)", adb("cmd media_session volume --stream 3 --get"))
        return int(m[1]) if m else None
    if name == "brightness":
        return adb("settings get system screen_brightness")
    if name == "top":
        m = re.search(r"([\w.]+)/[\w.$]+", adb("dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'"))
        return m[1] if m else None
    if name == "music":
        return "playing" if "state=PlaybackState {state=3" in adb("dumpsys media_session") else "stopped"
    return None


def check(case, before, after, reply):
    """Список нарушений ожиданий; пустой = PASS."""
    bad = []
    if "reply" in case and not re.search(case["reply"], reply, re.I):
        bad.append(f"ответ не подходит под /{case['reply']}/")
    if "no_reply" in case and re.search(case["no_reply"], reply, re.I):
        bad.append(f"плохой ответ /{case['no_reply']}/")
    if "route" in case and case["route"] != case.get("_route"):
        bad.append(f"маршрут {case.get('_route')} вместо {case['route']}")
    p = case.get("probe")
    if p:
        want = case.get("want")
        got = after
        if want == "up" and not (isinstance(got, int) and got > before):
            bad.append(f"{p}: {before} → {got}, ожидали рост")
        elif want == "down" and not (isinstance(got, int) and got < before):
            bad.append(f"{p}: {before} → {got}, ожидали снижение")
        elif want == "changed" and got == before:
            bad.append(f"{p}: не изменилось ({got})")
        elif want not in ("up", "down", "changed") and str(want) not in str(got):
            bad.append(f"{p}: {got}, ожидали {want}")
    return bad


def run_case(case, default_timeout):
    before = probe(case["probe"]) if case.get("probe") else None
    with lock:
        start = len(lines)
    send(case["say"])
    done, took = wait_turn(start, case.get("timeout", default_timeout))
    if not done:
        return dict(case, ok=False, bad=["нет ответа"], took=round(took, 1))
    case["_route"] = done[4]
    reply = done[5].rstrip('"')
    speech_end(start)
    time.sleep(case.get("settle", 1.0))
    after = probe(case["probe"]) if case.get("probe") else None
    bad = check(case, before, after, reply)
    shown = "[скрыто]" if case.get("private") else reply[:110]
    res = dict(id=case["id"], group=case["group"], say=case["say"], route=done[4], status=done[3],
               ms=int(done[2]), reply=shown, before=before, after=after, ok=not bad, bad=bad)
    for step in case.get("then", []):
        with lock:
            s2 = len(lines)
        send(step)
        d2, _ = wait_turn(s2, default_timeout)
        speech_end(s2)
        time.sleep(1.0)
        res.setdefault("then", []).append((step, "[скрыто]" if case.get("private") or not d2 else d2[5][:90]))
    return res


def snapshot():
    """Настройки, которые команды меняют: после прогона телефон возвращается к ним."""
    return dict(brightness=adb("settings get system screen_brightness"), volume=probe("volume"),
                zen=adb("settings get global zen_mode"))


def restore(state):
    adb(f"settings put system screen_brightness {state['brightness']}")
    adb(f"cmd media_session volume --stream 3 --set {state['volume']}")
    if state["zen"] == "0":
        adb("cmd notification set_dnd off")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only")
    ap.add_argument("--json")
    ap.add_argument("--file", help="другой список команд")
    ap.add_argument("--timeout", type=float, default=30)
    a = ap.parse_args()
    cases = json.load(open(a.file or os.path.join(os.path.dirname(__file__), "commands.json"), encoding="utf8"))
    if a.only:
        cases = [c for c in cases if c["group"] in a.only.split(",")]
    threading.Thread(target=logcat, daemon=True).start()
    time.sleep(1.5)
    results = []
    state = snapshot()
    for c in cases:
        r = run_case(c, a.timeout)
        results.append(r)
        print(("PASS" if r["ok"] else "FAIL"), f"{r.get('group',''):10} {r.get('ms',0):6}ms {r.get('route') or '-':11}",
              c["say"][:42].ljust(42), "→", str(r.get("reply", ""))[:80], "; ".join(r["bad"]), flush=True)
        adb("input keyevent KEYCODE_HOME") if c.get("home") else None
    restore(state)
    if reader:
        reader.terminate()
    ok = sum(r["ok"] for r in results)
    print(f"\nИтог: {ok}/{len(results)}")
    if a.json:
        json.dump(results, open(a.json, "w", encoding="utf8"), ensure_ascii=False, indent=1)
    sys.exit(0 if ok == len(results) else 1)


if __name__ == "__main__":
    main()
