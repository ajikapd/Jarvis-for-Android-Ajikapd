#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Замер облачных LLM для Jarvis (только стандартная библиотека Python).

Запуск (ключи только в переменных окружения, в скрипт они не передаются):
    python3 bench.py --dry-run
    python3 bench.py --preflight --providers groq
    python3 bench.py --providers groq --yes
    python3 bench.py --merge results/RUN_A results/RUN_B

Подробности в README.md.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import datetime as dt
import http.client
import json
import math
import os
import re
import socket
import ssl
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
from typing import Any, Dict, List, Optional, Tuple

HERE = os.path.dirname(os.path.abspath(__file__))
MAX_TOKENS = 1024

PROVIDERS: Dict[str, Dict[str, Any]] = {
    "gemini": {
        "key_env": "GEMINI_API_KEY",
        "base_env": "BENCH_GEMINI_BASE",
        "base": "https://generativelanguage.googleapis.com/v1beta/openai",
        "pause": 4.5,  # 15 запросов в минуту на модель
    },
    "groq": {
        "key_env": "GROQ_API_KEY",
        "base_env": "BENCH_GROQ_BASE",
        "base": "https://api.groq.com/openai/v1",
        "pause": 6.0,  # лимит 8K токенов в минуту на модель
    },
}

# Cloudflare перед Groq отвечает 403 "error code: 1010" на дефолтный Python-urllib.
DEFAULT_UA = "Mozilla/5.0 (X11; Linux x86_64) jarvis-bench/1.0"


def user_agent() -> str:
    return os.environ.get("BENCH_USER_AGENT") or DEFAULT_UA


# ---------------------------------------------------------------- секреты ---

def _secrets() -> List[str]:
    vals = [os.environ.get(p["key_env"], "") for p in PROVIDERS.values()]
    return [v for v in vals if len(v) >= 8]


def redact(text: Any) -> str:
    s = str(text)
    for k in _secrets():
        s = s.replace(k, "***")
    s = re.sub(r"(?i)(bearer\s+)[A-Za-z0-9_\-\.]{8,}", r"\1***", s)
    return s


# ---------------------------------------------------- размышления в тексте ---

_THOUGHT_RE = re.compile(r"<(thought|think|thinking)>.*?</\1>", re.S | re.I)
_OPEN_RE = re.compile(r"<(thought|think|thinking)>", re.I)


def split_thoughts(text: str) -> Tuple[str, List[str]]:
    """Возвращает (видимый текст, список вырезанных блоков размышлений)."""
    thoughts = [m.group(0) for m in _THOUGHT_RE.finditer(text)]
    clean = _THOUGHT_RE.sub("", text)
    m = _OPEN_RE.search(clean)
    if m:  # незакрытый блок: всё после тега считаем размышлением
        thoughts.append(clean[m.start():])
        clean = clean[:m.start()]
    return clean.strip(), thoughts


# --------------------------------------------------------------- токены ---

def parse_usage(u: Any) -> Tuple[Optional[int], Optional[int], Optional[int], Optional[int]]:
    """(prompt, completion, reasoning, total); отсутствующее значение = None."""
    if not isinstance(u, dict):
        return None, None, None, None
    det = u.get("completion_tokens_details")
    rs = det.get("reasoning_tokens") if isinstance(det, dict) else None
    return u.get("prompt_tokens"), u.get("completion_tokens"), rs, u.get("total_tokens")


# ------------------------------------------------------------ транспорт ---

def _consume(obj: Dict[str, Any], st: Dict[str, Any]) -> str:
    """Разбирает один SSE-объект (или целый ответ без стрима). Возвращает новый текст."""
    u = obj.get("usage")
    if not isinstance(u, dict):
        u = (obj.get("x_groq") or {}).get("usage") if isinstance(obj.get("x_groq"), dict) else None
    if isinstance(u, dict):
        st["usage"] = parse_usage(u)
    new = ""
    for ch in obj.get("choices") or []:
        d = ch.get("delta") or ch.get("message") or {}
        c = d.get("content")
        if isinstance(c, str) and c:
            new += c
        r = d.get("reasoning") or d.get("reasoning_content")
        if isinstance(r, str):
            st["reasoning_chars"] += len(r)
        if ch.get("finish_reason"):
            st["finish"] = ch["finish_reason"]
    return new


def stream_chat(provider: str, model: str, messages: List[Dict[str, str]],
                extra: Optional[Dict[str, Any]] = None, timeout: float = 60.0,
                max_tokens: int = MAX_TOKENS) -> Dict[str, Any]:
    """Один запрос. Никогда не бросает исключений.

    Если сервер отвечает 400 и жалуется на stream_options или max_tokens, запрос один раз
    повторяется без stream_options (usage тогда будет n/a) или с max_completion_tokens.
    """
    include_usage, tokens_key = True, "max_tokens"
    r = _stream_chat(provider, model, messages, extra, timeout, max_tokens, include_usage, tokens_key)
    for _ in range(2):
        if r.get("ok") or r.get("http") != 400:
            break
        low = (r.get("error") or "").lower()
        if "stream_options" in low and include_usage:
            include_usage = False
        elif "max_tokens" in low and tokens_key == "max_tokens":
            tokens_key = "max_completion_tokens"
        else:
            break
        r = _stream_chat(provider, model, messages, extra, timeout, max_tokens, include_usage, tokens_key)
    return r


def _stream_chat(provider: str, model: str, messages: List[Dict[str, str]],
                 extra: Optional[Dict[str, Any]], timeout: float, max_tokens: int,
                 include_usage: bool, tokens_key: str) -> Dict[str, Any]:
    cfg = PROVIDERS[provider]
    key = os.environ.get(cfg["key_env"], "")
    if not key:
        return {"ok": False, "http": None, "error": "missing_key", "missing_key": True}
    base = os.environ.get(cfg["base_env"]) or cfg["base"]
    url = base.rstrip("/") + "/chat/completions"
    body: Dict[str, Any] = {
        "model": model,
        "messages": messages,
        "stream": True,
        tokens_key: max_tokens,
        "temperature": 0.3,
    }
    if include_usage:
        body["stream_options"] = {"include_usage": True}
    if extra:
        body.update(extra)
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST", headers={
        "Authorization": "Bearer " + key,
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
        "User-Agent": user_agent(),
    })
    t0 = time.perf_counter()
    try:
        resp = urllib.request.urlopen(req, timeout=timeout)
    except urllib.error.HTTPError as e:
        try:
            txt = e.read(4000).decode("utf-8", "replace")
        except Exception:  # noqa: BLE001
            txt = ""
        hdrs = {k.lower(): v for k, v in (e.headers.items() if e.headers else [])}
        return {"ok": False, "http": e.code, "error": redact(txt)[:500], "headers": hdrs}
    except (urllib.error.URLError, socket.timeout, TimeoutError, ssl.SSLError,
            ConnectionError, http.client.HTTPException, OSError) as e:
        return {"ok": False, "http": None, "error": redact(repr(e))[:300]}

    st: Dict[str, Any] = {"usage": (None, None, None, None), "reasoning_chars": 0, "finish": None}
    raw_text = ""
    first_chunk = first_vis = last = None
    chunks = 0
    err = None
    ctype = (resp.headers.get("Content-Type") or "").lower()
    try:
        with resp:
            if "event-stream" in ctype:
                for raw in resp:
                    line = raw.decode("utf-8", "replace").strip()
                    if not line.startswith("data:"):
                        continue
                    payload = line[5:].strip()
                    if payload == "[DONE]":
                        break
                    try:
                        obj = json.loads(payload)
                    except ValueError:
                        continue
                    if not isinstance(obj, dict):
                        continue
                    now = (time.perf_counter() - t0) * 1000.0
                    chunks += 1
                    if first_chunk is None:
                        first_chunk = now
                    last = now
                    raw_text += _consume(obj, st)
                    if first_vis is None and split_thoughts(raw_text)[0]:
                        first_vis = now
            else:  # сервер не стримит: один JSON
                obj = json.loads(resp.read().decode("utf-8", "replace"))
                now = (time.perf_counter() - t0) * 1000.0
                chunks = 1
                first_chunk = last = now
                if isinstance(obj, dict):
                    raw_text += _consume(obj, st)
                if split_thoughts(raw_text)[0]:
                    first_vis = now
    except Exception as e:  # noqa: BLE001  (обрыв посреди потока)
        err = redact(repr(e))[:300]
    clean, thoughts = split_thoughts(raw_text)
    p_in, p_out, p_rs, p_total = st["usage"]
    if err and not clean:
        return {"ok": False, "http": None, "error": err}
    return {
        "ok": True, "http": 200, "text": clean, "thought_chars": sum(len(t) for t in thoughts),
        "thought_leak": bool(thoughts), "reasoning_chars": st["reasoning_chars"],
        "t_first_chunk": first_chunk, "t_first_visible": first_vis,
        "t_last_chunk": last if last is not None else (time.perf_counter() - t0) * 1000.0,
        "chunks": chunks, "in_tokens": p_in, "out_tokens": p_out, "reasoning_tokens": p_rs,
        "total_tokens": p_total, "finish": st["finish"], "stream": "event-stream" in ctype,
        "truncated_error": err,
    }


# ---------------------------------------------------- классификация ошибок ---

_DAILY_RE = re.compile(r"per\s*day|daily|\brpd\b|\btpd\b|requests per day|tokens per day", re.I)


def classify_error(status: Optional[int], body: Optional[str], has_key: bool = True) -> str:
    if not has_key:
        return "missing_key"
    text = body or ""
    if status is None:
        return "missing_key" if text == "missing_key" else "network_error"
    if status == 403 and "1010" in text:
        return "blocked_by_cloudflare_user_agent_or_region"
    if status in (401, 403):
        return "auth_or_region"
    if status == 429:
        return "rate_limited_daily" if _DAILY_RE.search(text) else "rate_limited"
    if status == 503:
        return "overloaded"
    if status in (500, 502, 504):
        return "server_error"
    if status == 400:
        return "bad_request"
    return "http_%d" % status


_RETRYABLE = {"rate_limited", "overloaded", "network_error", "server_error"}


def retry_after(r: Dict[str, Any]) -> Optional[float]:
    h = (r.get("headers") or {}).get("retry-after")
    if h:
        try:
            return float(h)
        except ValueError:
            pass
    txt = r.get("error") or ""
    m = re.search(r'"retryDelay"\s*:\s*"(\d+(?:\.\d+)?)s"', txt)
    if m:
        return float(m.group(1))
    m = re.search(r"try again in (\d+(?:\.\d+)?)\s*(ms|s)", txt, re.I)
    if m:
        v = float(m.group(1))
        return v / 1000.0 if m.group(2).lower() == "ms" else v
    return None


def call_with_retries(provider: str, model: str, messages: List[Dict[str, str]],
                      extra: Optional[Dict[str, Any]], args: argparse.Namespace
                      ) -> Tuple[Dict[str, Any], str, List[str]]:
    log: List[str] = []
    r: Dict[str, Any] = {}
    cls = "network_error"
    for i in range(args.retries + 1):
        r = stream_chat(provider, model, messages, extra, timeout=args.timeout)
        if r.get("ok"):
            log.append("ok")
            return r, "ok", log
        cls = classify_error(r.get("http"), r.get("error"), has_key=not r.get("missing_key"))
        log.append(cls)
        if cls not in _RETRYABLE or i == args.retries:
            break
        wait = retry_after(r)
        wait = min(wait, 60.0) if wait is not None else args.retry_sleep * (2 ** i)
        time.sleep(wait)
    return r, cls, log


# ---------------------------------------------------------------- оценка ---

def extract_json(text: str) -> Any:
    t = text.strip()
    m = re.search(r"```(?:json)?\s*(.*?)```", t, re.S | re.I)
    if m:
        t = m.group(1).strip()
    try:
        return json.loads(t)
    except ValueError:
        pass
    start = t.find("{")
    if start < 0:
        return None
    depth, instr, esc = 0, False, False
    for i in range(start, len(t)):
        ch = t[i]
        if instr:
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                instr = False
            continue
        if ch == '"':
            instr = True
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                try:
                    return json.loads(t[start:i + 1])
                except ValueError:
                    return None
    return None


def has_markdown(text: str) -> bool:
    return bool(re.search(r"```|\*\*|^\s*([-*•]|\d+[.)])\s|^\s*#{1,6}\s", text, re.M))


def score(p: Dict[str, Any], text: str, tool_names: List[str]) -> Optional[bool]:
    cat = p["category"]
    if cat == "memory":
        low = text.lower()
        return any(s in low for s in p.get("expect_any", []))
    if cat == "command":
        obj = extract_json(text)
        return (isinstance(obj, dict) and obj.get("tool") in tool_names
                and obj.get("tool") == p.get("expect_tool") and isinstance(obj.get("args", {}), dict))
    if cat == "plan":
        obj = extract_json(text)
        steps = obj.get("steps") if isinstance(obj, dict) else None
        return (isinstance(steps, list) and 1 <= len(steps) <= 3 and all(
            isinstance(s, dict) and s.get("tool") in tool_names and isinstance(s.get("args", {}), dict)
            for s in steps))
    return None


def build_messages(p: Dict[str, Any], cfg: Dict[str, Any]) -> List[Dict[str, str]]:
    system = cfg["system"]
    cat = p["category"]
    if cat == "memory":
        system += "\n\n[Память]\n" + "\n".join("- " + f for f in cfg["memory_facts"])
    if cat in ("command", "plan"):
        tools = "\n".join('- %s args=%s: %s' % (t["name"], json.dumps(t["args"], ensure_ascii=False), t["desc"])
                          for t in cfg["tools"])
        system += "\n\nДоступные инструменты:\n" + tools
        if cat == "command":
            system += '\nОтветь ТОЛЬКО JSON без пояснений: {"tool":"имя","args":{...}}'
        else:
            system += ('\nОтветь ТОЛЬКО JSON без пояснений: {"steps":[{"tool":"имя","args":{...}}]}, '
                       'не больше 3 шагов')
    msgs = [{"role": "system", "content": system}]
    if "messages" in p:
        msgs += p["messages"]
    else:
        msgs.append({"role": "user", "content": p["user"]})
    return msgs


def estimate_call_tokens(messages: List[Dict[str, str]], variant: str) -> int:
    chars = sum(len(m["content"]) for m in messages)
    return int(chars / 2.5) + 20 + 60 + (300 if variant == "default" else 40)


# ------------------------------------------------------------- выбор моделей ---

def select_models(models: List[Dict[str, Any]], providers: Optional[str], names: Optional[str],
                  include_heavy: bool, include_experimental: bool) -> List[Dict[str, Any]]:
    provs = {x.strip().lower() for x in (providers or "").split(",") if x.strip()}
    wanted = [x.strip().lower() for x in (names or "").split(",") if x.strip()]
    out = []
    for m in models:
        if provs and m["provider"].lower() not in provs:
            continue
        if wanted:
            ids = [m["model"].lower(), m.get("label", "").lower()]
            exact = any(w in ids for w in wanted)
            partial = any(w in i for w in wanted for i in ids)
            if not (exact or partial):
                continue
            out.append(m)  # явно названную модель берём даже heavy/experimental
            continue
        if m.get("heavy") and not include_heavy:
            continue
        if m.get("experimental") and not include_experimental:
            continue
        out.append(m)
    return out


def model_key(m: Dict[str, Any]) -> str:
    return "%s/%s" % (m["provider"], m["model"])


def pause_for(m: Dict[str, Any], args: argparse.Namespace) -> float:
    if args.pause is not None:
        return args.pause
    return float(m.get("pause_s", PROVIDERS[m["provider"]]["pause"]))


# --------------------------------------------------------------- preflight ---

PROBE = [{"role": "user", "content": "Ответь одним словом: ок"}]

HINTS = {
    "blocked_by_cloudflare_user_agent_or_region":
        "Cloudflare блокирует User-Agent или регион. Задай другой через BENCH_USER_AGENT; проверь сеть/VPN.",
    "auth_or_region": "Ключ не принят или недоступен регион.",
    "missing_key": "Переменная окружения с ключом не задана.",
    "rate_limited_daily": "Суточный лимит исчерпан, повтори после сброса.",
    "rate_limited": "Лимит в минуту, повтори позже.",
    "overloaded": "Сервис перегружен (503).",
    "network_error": "Сетевая ошибка (Connection refused, timeout, SSL). Проверь прокси/VPN/сеть.",
}


def preflight_model(m: Dict[str, Any], want_min: bool, args: argparse.Namespace) -> Dict[str, Any]:
    out: Dict[str, Any] = {"status": "ok", "min_status": "not_requested", "min_params": None}
    prov = m["provider"]
    r = stream_chat(prov, m["model"], PROBE, None, timeout=args.timeout, max_tokens=256)
    if not r.get("ok"):
        cls = classify_error(r.get("http"), r.get("error"), has_key=not r.get("missing_key"))
        out.update(status=cls, detail=redact(r.get("error") or "")[:200])
        return out
    out["ttft_ms"] = r.get("t_first_visible") or r.get("t_first_chunk")
    if want_min:
        cands = m.get("min_candidates") or []
        out["min_status"] = "unsupported"
        for cand in cands:
            time.sleep(pause_for(m, args))
            r2 = stream_chat(prov, m["model"], PROBE, cand, timeout=args.timeout, max_tokens=256)
            if r2.get("ok"):
                out.update(min_status="ok", min_params=cand)
                break
            cls2 = classify_error(r2.get("http"), r2.get("error"))
            if cls2 != "bad_request":
                out.update(min_status="probe_failed:" + cls2)
                break
    return out


def run_preflight(models: List[Dict[str, Any]], variants: List[str],
                  args: argparse.Namespace) -> Dict[str, Dict[str, Any]]:
    res: Dict[str, Dict[str, Any]] = {}
    last_provider_call: Dict[str, float] = {}
    for m in models:
        prov = m["provider"]
        gap = pause_for(m, args) - (time.time() - last_provider_call.get(prov, 0))
        if gap > 0 and prov in last_provider_call:
            time.sleep(gap)
        pf = preflight_model(m, "min" in variants, args)
        last_provider_call[prov] = time.time()
        res[model_key(m)] = pf
        if pf["status"] == "ok":
            ttft = pf.get("ttft_ms")
            extra = ""
            if "min" in variants:
                extra = " min=" + (json.dumps(pf["min_params"], ensure_ascii=False)
                                   if pf["min_params"] else pf["min_status"])
            print("PREFLIGHT %-42s OK ttft=%s ms%s" % (model_key(m), "n/a" if ttft is None else round(ttft), extra))
        else:
            print("PREFLIGHT %-42s FAILED %s  %s" % (model_key(m), pf["status"], HINTS.get(pf["status"], "")))
    return res


# ------------------------------------------------------------------- прогон ---

class Writer:
    def __init__(self, path: str) -> None:
        self.path = path
        self.lock = threading.Lock()

    def write(self, row: Dict[str, Any]) -> None:
        with self.lock, open(self.path, "a", encoding="utf-8") as f:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")


def run_model(m: Dict[str, Any], plan: List[Tuple[str, Optional[Dict[str, Any]]]], cfg: Dict[str, Any],
              args: argparse.Namespace, writer: Writer, log_lock: threading.Lock) -> Dict[str, Any]:
    status, consec, skipped, done = "ok", 0, 0, 0
    prompts = cfg["prompts"][: args.limit_prompts] if args.limit_prompts else cfg["prompts"]
    names = [t["name"] for t in cfg["tools"]]
    pause = pause_for(m, args)
    for variant, extra in plan:
        for p in prompts:
            if status != "ok":
                skipped += 1
                continue
            msgs = build_messages(p, cfg)
            r, cls, alog = call_with_retries(m["provider"], m["model"], msgs, extra, args)
            row: Dict[str, Any] = {
                "ts": dt.datetime.now(dt.timezone.utc).isoformat(), "provider": m["provider"],
                "model": m["model"], "label": m.get("label"), "variant": variant,
                "prompt_id": p["id"], "category": p["category"], "attempt_log": alog,
                "http": r.get("http"), "error": None,
            }
            if r.get("ok"):
                text = r["text"]
                row.update(
                    status="ok" if text else "empty_response", text=text, text_len=len(text),
                    ttft_first_chunk_ms=r["t_first_chunk"], ttft_visible_ms=r["t_first_visible"],
                    total_ms=r["t_last_chunk"], chunks=r["chunks"], stream=r["stream"],
                    in_tokens=r["in_tokens"], out_tokens=r["out_tokens"],
                    reasoning_tokens=r["reasoning_tokens"], total_tokens=r["total_tokens"],
                    finish_reason=r["finish"], thought_leak=r["thought_leak"],
                    thought_chars=r["thought_chars"], has_markdown=has_markdown(text),
                    auto_pass=score(p, text, names) if text else (False if score(p, "x", names) is not None else None),
                )
                consec = 0
            else:
                row.update(status=cls, error=redact(r.get("error") or "")[:300], text="", auto_pass=None)
                if cls == "rate_limited_daily":
                    status = "daily_quota_exhausted"
                elif cls in ("missing_key",):
                    status = "missing_key"
                else:
                    consec += 1
                    if consec >= 3:
                        status = "circuit_open"
            writer.write(row)
            done += 1
            with log_lock:
                print("  %-34s %-8s %-12s %-9s %s" % (model_key(m), variant, p["id"], row["status"],
                      "" if row.get("ttft_visible_ms") is None else "%d ms" % row["ttft_visible_ms"]))
            time.sleep(pause)
    return {"status": status, "skipped": skipped, "done": done}


# --------------------------------------------------------------------- отчёты ---

def _med(xs: List[float]) -> Optional[float]:
    return statistics.median(xs) if xs else None


def _p90(xs: List[float]) -> Optional[float]:
    if not xs:
        return None
    s = sorted(xs)
    return s[max(0, math.ceil(0.9 * len(s)) - 1)]


def _fmt(x: Optional[float]) -> str:
    return "—" if x is None else str(int(round(x)))


def _avg(vals: List[Optional[int]]) -> str:
    v = [x for x in vals if x is not None]
    return "n/a" if not v else str(int(round(sum(v) / len(v))))


def aggregate(rows: List[Dict[str, Any]], meta: Dict[str, Any]) -> List[Dict[str, Any]]:
    groups: Dict[Tuple[str, str, str], List[Dict[str, Any]]] = {}
    for r in rows:
        groups.setdefault((r["provider"], r["model"], r["variant"]), []).append(r)
    out = []
    for (prov, model, variant), rs in sorted(groups.items()):
        ok = [r for r in rs if r["status"] == "ok"]
        chunks = [r["chunks"] for r in ok if r.get("chunks") is not None]
        scores = {}
        for cat in ("memory", "command", "plan"):
            cr = [r for r in rs if r["category"] == cat]
            scores[cat] = "—" if not cr else "%d/%d" % (sum(1 for r in cr if r.get("auto_pass")), len(cr))
        att = [a for r in rs for a in r.get("attempt_log", [])]
        mstat = (meta.get("models", {}).get("%s/%s" % (prov, model), {}) or {}).get("status", "ok")
        out.append({
            "key": "%s/%s" % (prov, model), "variant": variant, "status": mstat, "n": len(rs), "ok": len(ok),
            "ttft_chunk": _med([r["ttft_first_chunk_ms"] for r in ok if r.get("ttft_first_chunk_ms") is not None]),
            "ttft_text": _med([r["ttft_visible_ms"] for r in ok if r.get("ttft_visible_ms") is not None]),
            "p90": _p90([r["ttft_visible_ms"] for r in ok if r.get("ttft_visible_ms") is not None]),
            "total": _med([r["total_ms"] for r in ok if r.get("total_ms") is not None]),
            "chunks_med": _med(chunks),
            "no_stream": bool(chunks) and all(c <= 1 for c in chunks),
            "in": _avg([r.get("in_tokens") for r in ok]), "out": _avg([r.get("out_tokens") for r in ok]),
            "reason": _avg([r.get("reasoning_tokens") for r in ok]), "tot": _avg([r.get("total_tokens") for r in ok]),
            "mem": scores["memory"], "cmd": scores["command"], "plan": scores["plan"],
            "leak": 100.0 * sum(1 for r in ok if r.get("thought_leak")) / len(ok) if ok else 0.0,
            "md": 100.0 * sum(1 for r in ok if r.get("has_markdown")) / len(ok) if ok else 0.0,
            "n429": sum(1 for a in att if a.startswith("rate_limited")),
            "n503": sum(1 for a in att if a == "overloaded"),
        })
    return out


def write_reports(out_dir: str, rows: List[Dict[str, Any]], meta: Dict[str, Any]) -> None:
    agg = aggregate(rows, meta)
    L = ["# Cloud bench summary", "", "Generated: " + dt.datetime.now(dt.timezone.utc).isoformat(), ""]
    L += ["## Preflight / статусы моделей", ""]
    for k, v in sorted(meta.get("models", {}).items()):
        pf = v.get("preflight") or {}
        L.append("- `%s`: status=%s skipped=%s min=%s%s" % (
            k, v.get("status"), v.get("skipped", 0),
            json.dumps(v.get("min_params"), ensure_ascii=False) if v.get("min_params") else v.get("min_status", "n/a"),
            ("  detail=" + pf["detail"]) if pf.get("detail") else ""))
    L += ["", "## Результаты (медианы по успешным ответам, мс)", "",
          "| модель | вариант | статус | n | ok | TTFT 1-й чанк | TTFT текст | p90 TTFT | всего | чанков | стрим | in | out | reason | total | mem | cmd | plan | leak% | md% | 429 | 503 |",
          "|---|---|---|---:|---:|---:|---:|---:|---:|---:|---|---:|---:|---:|---:|---|---|---|---:|---:|---:|---:|"]
    for a in agg:
        L.append("| %s | %s | %s | %d | %d | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %.0f | %.0f | %d | %d |" % (
            a["key"], a["variant"], a["status"], a["n"], a["ok"], _fmt(a["ttft_chunk"]), _fmt(a["ttft_text"]),
            _fmt(a["p90"]), _fmt(a["total"]), _fmt(a["chunks_med"]), "no-stream" if a["no_stream"] else "ok",
            a["in"], a["out"], a["reason"], a["tot"], a["mem"], a["cmd"], a["plan"], a["leak"], a["md"],
            a["n429"], a["n503"]))
    L += ["", "n/a = провайдер не отдал usage. total берётся из usage.total_tokens (оно авторитетно: "
          "completion может включать или не включать размышления в зависимости от провайдера)."]
    with open(os.path.join(out_dir, "summary.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(L) + "\n")
    S = ["CLOUD BENCH SHORT REPORT", "ts=" + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ"), ""]
    for a in agg:
        S.append("%s [%s]: status=%s ok=%d/%d ttft_text_med=%s p90=%s total_med=%s chunks_med=%s%s "
                 "tok(in/out/reason/total)=%s/%s/%s/%s mem=%s cmd=%s plan=%s leak=%.0f%% md=%.0f%% 429=%d 503=%d" % (
                     a["key"], a["variant"], a["status"], a["ok"], a["n"], _fmt(a["ttft_text"]), _fmt(a["p90"]),
                     _fmt(a["total"]), _fmt(a["chunks_med"]), " NO-STREAM" if a["no_stream"] else "",
                     a["in"], a["out"], a["reason"], a["tot"], a["mem"], a["cmd"], a["plan"], a["leak"], a["md"],
                     a["n429"], a["n503"]))
    for k, v in sorted(meta.get("models", {}).items()):
        if v.get("status") != "ok":
            S.append("MODEL %s: %s (skipped=%s)" % (k, v.get("status"), v.get("skipped", 0)))
    S += ["", "Send to Claude: this file + results.jsonl"]
    with open(os.path.join(out_dir, "short_report.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(S) + "\n")


def load_run(d: str) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    rows = []
    p = os.path.join(d, "results.jsonl")
    if os.path.exists(p):
        with open(p, encoding="utf-8") as f:
            rows = [json.loads(x) for x in f if x.strip()]
    meta: Dict[str, Any] = {"models": {}}
    mp = os.path.join(d, "run_meta.json")
    if os.path.exists(mp):
        with open(mp, encoding="utf-8") as f:
            meta = json.load(f)
    return rows, meta


def merge_dirs(dirs: List[str], out_dir: str) -> None:
    merged: Dict[Tuple[str, str, str, str], Dict[str, Any]] = {}
    meta: Dict[str, Any] = {"models": {}}
    for d in dirs:  # поздние каталоги в списке побеждают
        rows, m = load_run(d)
        for r in rows:
            merged[(r["provider"], r["model"], r["variant"], r["prompt_id"])] = r
        meta["models"].update(m.get("models", {}))
    os.makedirs(out_dir, exist_ok=True)
    rows = list(merged.values())
    with open(os.path.join(out_dir, "results.jsonl"), "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    with open(os.path.join(out_dir, "run_meta.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
    write_reports(out_dir, rows, meta)
    print("merged %d rows from %d runs -> %s" % (len(rows), len(dirs), out_dir))


# ---------------------------------------------------------------------- main ---

def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    ap = argparse.ArgumentParser(description="Cloud LLM bench for Jarvis")
    ap.add_argument("--models-file", default=os.path.join(HERE, "models.json"))
    ap.add_argument("--prompts-file", default=os.path.join(HERE, "prompts.json"))
    ap.add_argument("--providers", default="", help="gemini,groq (по умолчанию все)")
    ap.add_argument("--models", default="", help="id или часть названия, через запятую")
    ap.add_argument("--variants", default="default,min", help="default,min")
    ap.add_argument("--limit-prompts", type=int, default=0)
    ap.add_argument("--out", default=os.path.join(HERE, "results"))
    ap.add_argument("--pause", type=float, default=None, help="пауза между запросами к одной модели, с")
    ap.add_argument("--timeout", type=float, default=60.0)
    ap.add_argument("--retries", type=int, default=2)
    ap.add_argument("--retry-sleep", type=float, default=3.0)
    ap.add_argument("--workers", type=int, default=5, help="сколько моделей гонять параллельно")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--yes", action="store_true")
    ap.add_argument("--preflight", action="store_true", help="только проверка доступности")
    ap.add_argument("--no-preflight", action="store_true")
    ap.add_argument("--force", action="store_true", help="гонять модели, провалившие preflight")
    ap.add_argument("--include-heavy", action="store_true")
    ap.add_argument("--include-experimental", action="store_true")
    ap.add_argument("--merge", nargs="+", metavar="DIR", help="объединить прогоны (поздние побеждают)")
    return ap.parse_args(argv)


def main(argv: Optional[List[str]] = None) -> int:
    args = parse_args(argv)
    ts = dt.datetime.now().strftime("%Y%m%dT%H%M%S")
    if args.merge:
        merge_dirs(args.merge, os.path.join(args.out, ts + "_merged"))
        return 0
    with open(args.models_file, encoding="utf-8") as f:
        all_models = json.load(f)
    with open(args.prompts_file, encoding="utf-8") as f:
        cfg = json.load(f)
    models = select_models(all_models, args.providers, args.models, args.include_heavy, args.include_experimental)
    variants = [v.strip() for v in args.variants.split(",") if v.strip()]
    if not models:
        print("Нет моделей под выбранные фильтры.")
        return 2
    prompts = cfg["prompts"][: args.limit_prompts] if args.limit_prompts else cfg["prompts"]
    print("План: %d моделей, %d фраз, варианты %s" % (len(models), len(prompts), variants))
    warn = []
    for m in models:
        calls = len(prompts) * len(variants)
        est = sum(estimate_call_tokens(build_messages(p, cfg), v) for p in prompts for v in variants)
        lim = m.get("limits", {})
        print("  %-36s вызовов≈%-4d токенов≈%-7d (RPD %s, TPD %s)%s" % (
            model_key(m), calls, est, lim.get("rpd", "?"), lim.get("tpd", "?"),
            "  [heavy]" if m.get("heavy") else ("  [experimental]" if m.get("experimental") else "")))
        if lim.get("rpd") and calls > lim["rpd"]:
            warn.append("%s: вызовов %d > RPD %d" % (model_key(m), calls, lim["rpd"]))
        if lim.get("tpd") and est > lim["tpd"]:
            warn.append("%s: токенов≈%d > TPD %d" % (model_key(m), est, lim["tpd"]))
        for v in variants:
            print("     PLAN %s [%s]" % (model_key(m), v))
    for w in warn:
        print("ВНИМАНИЕ:", w)
    if args.dry_run:
        print("--dry-run: ничего не отправлено.")
        return 0

    os.makedirs(args.out, exist_ok=True)
    out_dir = os.path.join(args.out, ts)
    pf: Dict[str, Dict[str, Any]] = {}
    if not args.no_preflight or args.preflight:
        pf = run_preflight(models, variants, args)
    if args.preflight:
        return 0

    if not args.yes:
        if not sys.stdin.isatty():
            print("Нужно подтверждение: запусти с --yes.")
            return 2
        if input("Запустить полный прогон? [y/N] ").strip().lower() not in ("y", "yes", "д", "да"):
            print("Отменено.")
            return 1

    os.makedirs(out_dir, exist_ok=True)
    writer = Writer(os.path.join(out_dir, "results.jsonl"))
    meta: Dict[str, Any] = {"models": {}, "created": ts}
    jobs = []
    for m in models:
        k = model_key(m)
        info = pf.get(k, {})
        meta["models"][k] = {"status": "ok", "skipped": 0, "preflight": info,
                             "min_params": info.get("min_params"), "min_status": info.get("min_status")}
        if info and info.get("status") != "ok" and not args.force:
            meta["models"][k]["status"] = "preflight_failed:" + info["status"]
            print("SKIP %s: preflight %s (--force чтобы всё равно запустить)" % (k, info["status"]))
            continue
        plan: List[Tuple[str, Optional[Dict[str, Any]]]] = []
        for v in variants:
            if v == "default":
                plan.append(("default", None))
            elif v == "min":
                if info:
                    if info.get("min_params"):
                        plan.append(("min", info["min_params"]))
                    else:
                        print("SKIP %s [min]: %s" % (k, info.get("min_status")))
                else:  # без preflight берём первого кандидата
                    cands = m.get("min_candidates") or []
                    if cands:
                        plan.append(("min", cands[0]))
        if plan:
            jobs.append((m, plan))
    log_lock = threading.Lock()
    workers = max(1, min(args.workers, len(jobs) or 1))
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as ex:
        futs = {ex.submit(run_model, m, plan, cfg, args, writer, log_lock): m for m, plan in jobs}
        try:
            for fu in concurrent.futures.as_completed(futs):
                m = futs[fu]
                res = fu.result()
                meta["models"][model_key(m)].update(status=res["status"], skipped=res["skipped"])
        except KeyboardInterrupt:
            print("Прервано: сохраняю то, что есть.")
            ex.shutdown(wait=False, cancel_futures=True)
    rows, _ = load_run(out_dir)
    with open(os.path.join(out_dir, "run_meta.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
    write_reports(out_dir, rows, meta)
    print("Готово: %s" % out_dir)
    print("Пришли Claude: short_report.txt и results.jsonl")
    return 0


if __name__ == "__main__":
    sys.exit(main())
