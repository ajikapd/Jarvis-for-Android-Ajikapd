#!/usr/bin/env python3
"""Cloud LLM bench for Jarvis — stdlib only. Never prints API keys."""

from __future__ import annotations

import argparse
import json
import os
import re
import statistics
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Dict, Iterable, List, Optional, Sequence, Tuple

HERE = Path(__file__).resolve().parent
DEFAULT_MODELS = HERE / "models.json"
DEFAULT_PROMPTS = HERE / "prompts.json"
REQUEST_TIMEOUT_S = 60
DEFAULT_PAUSE_S = 4.0
MAX_RETRIES_TRANSIENT = 2  # retries after first attempt → up to 3 attempts

GEMINI_OPENAI_URL = (
    "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
)
GEMINI_NATIVE_TMPL = (
    "https://generativelanguage.googleapis.com/v1beta/models/"
    "{model}:streamGenerateContent?alt=sse"
)
GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"

# Approximate chars/token for budget estimate (conservative).
CHARS_PER_TOKEN = 3.5
THINKING_MULTIPLIER = 3.0


# ── Redaction ──────────────────────────────────────────────────────


def collect_secrets(env: Optional[Dict[str, str]] = None) -> List[str]:
    src = env if env is not None else os.environ
    keys = []
    for name in ("GEMINI_API_KEY", "GROQ_API_KEY", "MISTRAL_API_KEY"):
        v = (src.get(name) or "").strip()
        if v:
            keys.append(v)
    return keys


def redact_secrets(text: str, secrets: Optional[Sequence[str]] = None) -> str:
    if text is None:
        return ""
    out = str(text)
    secrets = list(secrets) if secrets is not None else collect_secrets()
    for s in sorted(secrets, key=len, reverse=True):
        if s:
            out = out.replace(s, "[REDACTED]")
    out = re.sub(r"(?i)(Authorization:\s*Bearer\s+)\S+", r"\1[REDACTED]", out)
    out = re.sub(r"(?i)(Bearer\s+)\S+", r"\1[REDACTED]", out)
    out = re.sub(r'(?i)("Authorization"\s*:\s*")[^"]+', r"\1[REDACTED]", out)
    out = re.sub(r"(?i)(x-goog-api-key:\s*)\S+", r"\1[REDACTED]", out)
    out = re.sub(r'(?i)("x-goog-api-key"\s*:\s*")[^"]+', r"\1[REDACTED]", out)
    out = re.sub(r"(?i)(api[_-]?key=)[^&\s]+", r"\1[REDACTED]", out)
    return out


def safe_error_message(exc: BaseException, secrets: Optional[Sequence[str]] = None) -> str:
    return redact_secrets(f"{type(exc).__name__}: {exc}", secrets)


# ── SSE parsers ────────────────────────────────────────────────────


@dataclass
class StreamResult:
    text: str = ""
    reasoning_text: str = ""
    ttft_ms: Optional[float] = None
    total_ms: float = 0.0
    in_tokens: Optional[int] = None
    out_tokens: Optional[int] = None
    reasoning_tokens: Optional[int] = None
    finish_reason: Optional[str] = None
    raw_events: int = 0
    transport: str = ""
    error: Optional[str] = None
    http_status: Optional[int] = None


def _now_ms() -> float:
    return time.perf_counter() * 1000.0


def iter_sse_data_lines(raw: str) -> Iterable[str]:
    """Yield payload strings from an SSE body (data: ...)."""
    buf = []
    for line in raw.splitlines():
        if line.startswith(":"):
            continue
        if line.startswith("data:"):
            payload = line[5:].lstrip()
            if payload == "[DONE]":
                if buf:
                    yield "\n".join(buf)
                    buf = []
                continue
            buf.append(payload)
            continue
        if line.strip() == "":
            if buf:
                yield "\n".join(buf)
                buf = []
            continue
        # continuation of multi-line data
        if buf:
            buf.append(line)
    if buf:
        yield "\n".join(buf)


def _extract_usage(obj: Dict[str, Any]) -> Tuple[Optional[int], Optional[int], Optional[int]]:
    usage = obj.get("usage") or {}
    if not isinstance(usage, dict):
        return None, None, None
    inn = usage.get("prompt_tokens")
    if inn is None:
        inn = usage.get("input_tokens")
    out = usage.get("completion_tokens")
    if out is None:
        out = usage.get("output_tokens")
    reason = usage.get("reasoning_tokens")
    if reason is None:
        details = usage.get("completion_tokens_details") or usage.get("output_tokens_details") or {}
        if isinstance(details, dict):
            reason = details.get("reasoning_tokens")
    # Gemini native
    meta = obj.get("usageMetadata") or {}
    if isinstance(meta, dict):
        if inn is None:
            inn = meta.get("promptTokenCount")
        if out is None:
            out = meta.get("candidatesTokenCount")
        if reason is None:
            reason = meta.get("thoughtsTokenCount")
        if out is None and meta.get("totalTokenCount") is not None and inn is not None:
            total = meta.get("totalTokenCount")
            thoughts = meta.get("thoughtsTokenCount") or 0
            try:
                out = int(total) - int(inn) - int(thoughts)
            except (TypeError, ValueError):
                pass
    try:
        inn_i = int(inn) if inn is not None else None
    except (TypeError, ValueError):
        inn_i = None
    try:
        out_i = int(out) if out is not None else None
    except (TypeError, ValueError):
        out_i = None
    try:
        reason_i = int(reason) if reason is not None else None
    except (TypeError, ValueError):
        reason_i = None
    return inn_i, out_i, reason_i


def parse_openai_sse_chunk(
    data: str,
    started_ms: float,
    state: Dict[str, Any],
) -> None:
    """Update state from one OpenAI-compatible SSE JSON payload."""
    if not data or data == "[DONE]":
        return
    try:
        obj = json.loads(data)
    except json.JSONDecodeError:
        return
    state["events"] = state.get("events", 0) + 1
    inn, out, reason = _extract_usage(obj)
    if inn is not None:
        state["in_tokens"] = inn
    if out is not None:
        state["out_tokens"] = out
    if reason is not None:
        state["reasoning_tokens"] = reason

    choices = obj.get("choices") or []
    if not choices and "error" in obj:
        state["error"] = str(obj.get("error"))
        return
    for ch in choices:
        if not isinstance(ch, dict):
            continue
        if ch.get("finish_reason"):
            state["finish_reason"] = ch.get("finish_reason")
        delta = ch.get("delta") or {}
        msg = ch.get("message") or {}
        # Visible content only (not reasoning).
        content = delta.get("content")
        if content is None and not delta:
            content = msg.get("content")
        if isinstance(content, list):
            # rare multimodal shape
            parts = []
            for p in content:
                if isinstance(p, dict) and p.get("type") == "text":
                    parts.append(p.get("text") or "")
                elif isinstance(p, str):
                    parts.append(p)
            content = "".join(parts)
        if isinstance(content, str) and content:
            if state.get("ttft_ms") is None:
                state["ttft_ms"] = _now_ms() - started_ms
            state["text"] = state.get("text", "") + content

        for key in ("reasoning", "reasoning_content"):
            r = delta.get(key)
            if r is None:
                r = msg.get(key)
            if isinstance(r, str) and r:
                state["reasoning_text"] = state.get("reasoning_text", "") + r


def parse_gemini_native_sse_chunk(
    data: str,
    started_ms: float,
    state: Dict[str, Any],
) -> None:
    if not data:
        return
    try:
        obj = json.loads(data)
    except json.JSONDecodeError:
        return
    state["events"] = state.get("events", 0) + 1
    inn, out, reason = _extract_usage(obj)
    if inn is not None:
        state["in_tokens"] = inn
    if out is not None:
        state["out_tokens"] = out
    if reason is not None:
        state["reasoning_tokens"] = reason
    if obj.get("error"):
        state["error"] = str(obj.get("error"))
        return
    for cand in obj.get("candidates") or []:
        content = (cand or {}).get("content") or {}
        for part in content.get("parts") or []:
            if not isinstance(part, dict):
                continue
            is_thought = bool(part.get("thought") or part.get("thoughts"))
            txt = part.get("text")
            if not isinstance(txt, str) or not txt:
                continue
            if is_thought:
                state["reasoning_text"] = state.get("reasoning_text", "") + txt
                continue
            if state.get("ttft_ms") is None:
                state["ttft_ms"] = _now_ms() - started_ms
            state["text"] = state.get("text", "") + txt
        fr = cand.get("finishReason")
        if fr:
            state["finish_reason"] = fr


def parse_sse_stream(
    raw_body: str,
    *,
    kind: str,
    started_ms: Optional[float] = None,
) -> StreamResult:
    """Parse a full SSE body (for tests / completed reads)."""
    started = started_ms if started_ms is not None else _now_ms()
    state: Dict[str, Any] = {"text": "", "reasoning_text": ""}
    parser = parse_openai_sse_chunk if kind == "openai" else parse_gemini_native_sse_chunk
    for payload in iter_sse_data_lines(raw_body):
        parser(payload, started, state)
    total = _now_ms() - started
    return StreamResult(
        text=state.get("text", ""),
        reasoning_text=state.get("reasoning_text", ""),
        ttft_ms=state.get("ttft_ms"),
        total_ms=total,
        in_tokens=state.get("in_tokens"),
        out_tokens=state.get("out_tokens"),
        reasoning_tokens=state.get("reasoning_tokens"),
        finish_reason=state.get("finish_reason"),
        raw_events=state.get("events", 0),
        transport=kind,
        error=state.get("error"),
    )


# ── Scoring / JSON validators ──────────────────────────────────────


_MD_RE = re.compile(r"(`{1,3}|\*{1,2}|_{1,2}|^#{1,6}\s|^\s*[-*+]\s|\[[^\]]+\]\([^)]+\))", re.M)


def has_markdown(text: str) -> bool:
    return bool(_MD_RE.search(text or ""))


def extract_json_object(text: str) -> Optional[Any]:
    if not text:
        return None
    s = text.strip()
    # strip ```json fences if present
    fence = re.search(r"```(?:json)?\s*([\s\S]*?)```", s, re.I)
    if fence:
        s = fence.group(1).strip()
    try:
        return json.loads(s)
    except json.JSONDecodeError:
        pass
    # find first {...}
    start = s.find("{")
    end = s.rfind("}")
    if start >= 0 and end > start:
        try:
            return json.loads(s[start : end + 1])
        except json.JSONDecodeError:
            return None
    return None


def tool_names(tools: Sequence[Dict[str, Any]]) -> List[str]:
    return [t["name"] for t in tools if isinstance(t, dict) and t.get("name")]


def score_memory(text: str, expect_any: Sequence[str]) -> Optional[bool]:
    if not expect_any:
        return None
    low = (text or "").lower()
    return any(e.lower() in low for e in expect_any)


def score_command(
    text: str,
    tools: Sequence[Dict[str, Any]],
    expect_tool: Optional[str] = None,
) -> Optional[bool]:
    obj = extract_json_object(text)
    if not isinstance(obj, dict):
        return False
    if "tool" not in obj or "args" not in obj:
        return False
    if not isinstance(obj.get("args"), dict):
        return False
    name = obj.get("tool")
    if name not in tool_names(tools):
        return False
    if expect_tool and name != expect_tool:
        return False
    return True


def score_plan(text: str, tools: Sequence[Dict[str, Any]]) -> Optional[bool]:
    obj = extract_json_object(text)
    if not isinstance(obj, dict):
        return False
    steps = obj.get("steps")
    if not isinstance(steps, list) or not (2 <= len(steps) <= 3):
        return False
    allowed = set(tool_names(tools))
    for step in steps:
        if not isinstance(step, dict):
            return False
        if "tool" not in step or "args" not in step:
            return False
        if not isinstance(step.get("args"), dict):
            return False
        if step.get("tool") not in allowed:
            return False
    return True


def auto_score(
    category: str,
    text: str,
    prompt: Dict[str, Any],
    tools: Sequence[Dict[str, Any]],
) -> Optional[bool]:
    if category == "memory":
        return score_memory(text, prompt.get("expect_any") or [])
    if category == "command":
        return score_command(text, tools, prompt.get("expect_tool"))
    if category == "plan":
        return score_plan(text, tools)
    return None


# ── Prompt assembly ────────────────────────────────────────────────


def format_tools_block(tools: Sequence[Dict[str, Any]]) -> str:
    lines = ["Доступные инструменты (используй только эти name):"]
    for t in tools:
        lines.append(
            f"- {t['name']}: {t.get('desc', '')}; args={json.dumps(t.get('args', {}), ensure_ascii=False)}"
        )
    return "\n".join(lines)


def build_messages(
    prompt: Dict[str, Any],
    *,
    system: str,
    memory_facts: Sequence[str],
    tools: Sequence[Dict[str, Any]],
) -> List[Dict[str, str]]:
    cat = prompt["category"]
    msgs: List[Dict[str, str]] = [{"role": "system", "content": system}]

    if cat == "memory":
        mem = "[Память]\n" + "\n".join(f"- {f}" for f in memory_facts)
        msgs.append({"role": "user", "content": mem + "\n\n" + prompt["user"]})
        return msgs

    if cat == "command":
        instr = (
            format_tools_block(tools)
            + '\nОтветь ТОЛЬКО JSON вида {"tool":"...","args":{...}} без пояснений.'
        )
        msgs.append({"role": "user", "content": instr + "\n\nЗапрос: " + prompt["user"]})
        return msgs

    if cat == "plan":
        instr = (
            format_tools_block(tools)
            + '\nСоставь план из 2–3 шагов. Ответь ТОЛЬКО JSON '
            '{"steps":[{"tool":"...","args":{...}}, ...]} без пояснений.'
        )
        msgs.append({"role": "user", "content": instr + "\n\nЗадача: " + prompt["user"]})
        return msgs

    if cat == "followup":
        for m in prompt.get("messages") or []:
            msgs.append({"role": m["role"], "content": m["content"]})
        return msgs

    msgs.append({"role": "user", "content": prompt["user"]})
    return msgs


def estimate_prompt_tokens(messages: Sequence[Dict[str, str]]) -> int:
    chars = sum(len(m.get("content") or "") + 8 for m in messages)
    return max(1, int(chars / CHARS_PER_TOKEN))


# ── Thinking / reasoning variants ──────────────────────────────────


@dataclass
class VariantSpec:
    name: str  # default | minimal-thinking | unsupported
    openai_body_extra: Dict[str, Any] = field(default_factory=dict)
    native_generation_config: Dict[str, Any] = field(default_factory=dict)
    probe_label: str = ""
    supported: bool = True


def default_variant() -> VariantSpec:
    return VariantSpec(name="default", probe_label="omit_reasoning_params", supported=True)


def gemini_minimal_candidates() -> List[VariantSpec]:
    """Ordered probes for Gemini minimal thinking (docs + fallbacks)."""
    return [
        VariantSpec(
            name="minimal-thinking",
            openai_body_extra={"reasoning_effort": "none"},
            native_generation_config={
                "thinkingConfig": {"thinkingBudget": 0},
            },
            probe_label="reasoning_effort=none / thinkingBudget=0",
        ),
        VariantSpec(
            name="minimal-thinking",
            openai_body_extra={"reasoning_effort": "minimal"},
            native_generation_config={
                "thinkingConfig": {"thinkingLevel": "MINIMAL"},
            },
            probe_label="reasoning_effort=minimal / thinkingLevel=MINIMAL",
        ),
        VariantSpec(
            name="minimal-thinking",
            openai_body_extra={"reasoning_effort": "low"},
            native_generation_config={
                "thinkingConfig": {"thinkingLevel": "LOW"},
            },
            probe_label="reasoning_effort=low / thinkingLevel=LOW",
        ),
        VariantSpec(
            name="minimal-thinking",
            openai_body_extra={
                "extra_body": {
                    "google": {"thinking_config": {"thinking_level": "minimal"}},
                }
            },
            native_generation_config={
                "thinkingConfig": {"thinkingLevel": "minimal"},
            },
            probe_label="extra_body.google.thinking_config.thinking_level=minimal",
        ),
    ]


def groq_minimal_candidates(model: str) -> List[VariantSpec]:
    m = model.lower()
    if "qwen" in m:
        return [
            VariantSpec(
                name="minimal-thinking",
                openai_body_extra={"reasoning_effort": "none"},
                probe_label="reasoning_effort=none",
            ),
        ]
    if "gpt-oss" in m:
        return [
            VariantSpec(
                name="minimal-thinking",
                openai_body_extra={"reasoning_effort": "low", "include_reasoning": False},
                probe_label="reasoning_effort=low include_reasoning=false",
            ),
        ]
    return [
        VariantSpec(
            name="minimal-thinking",
            openai_body_extra={"reasoning_effort": "none"},
            probe_label="reasoning_effort=none",
        ),
    ]


# ── HTTP transport ─────────────────────────────────────────────────


@dataclass
class HttpAttempt:
    status: Optional[int]
    error: Optional[str] = None
    transport: str = ""


@dataclass
class CallResult:
    status: str  # ok | error | unsupported | skipped
    attempts: List[HttpAttempt] = field(default_factory=list)
    stream: StreamResult = field(default_factory=StreamResult)
    variant_probe: str = ""
    transport: str = ""


def _http_stream(
    url: str,
    headers: Dict[str, str],
    body: Dict[str, Any],
    *,
    kind: str,
    secrets: Sequence[str],
) -> Tuple[int, StreamResult, Optional[str]]:
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST")
    for k, v in headers.items():
        req.add_header(k, v)
    req.add_header("Content-Type", "application/json")
    started = _now_ms()
    state: Dict[str, Any] = {"text": "", "reasoning_text": ""}
    parser = parse_openai_sse_chunk if kind == "openai" else parse_gemini_native_sse_chunk
    try:
        with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT_S) as resp:
            status = getattr(resp, "status", None) or resp.getcode()
            # Read SSE incrementally
            pending = ""
            while True:
                chunk = resp.read(1024)
                if not chunk:
                    break
                pending += chunk.decode("utf-8", errors="replace")
                while True:
                    if "\n" not in pending:
                        break
                    line, pending = pending.split("\n", 1)
                    line = line.rstrip("\r")
                    if line.startswith("data:"):
                        payload = line[5:].lstrip()
                        if payload == "[DONE]":
                            continue
                        parser(payload, started, state)
                    elif line.strip() == "":
                        continue
            if pending.strip().startswith("data:"):
                payload = pending.strip()[5:].lstrip()
                if payload and payload != "[DONE]":
                    parser(payload, started, state)
            total = _now_ms() - started
            sr = StreamResult(
                text=state.get("text", ""),
                reasoning_text=state.get("reasoning_text", ""),
                ttft_ms=state.get("ttft_ms"),
                total_ms=total,
                in_tokens=state.get("in_tokens"),
                out_tokens=state.get("out_tokens"),
                reasoning_tokens=state.get("reasoning_tokens"),
                finish_reason=state.get("finish_reason"),
                raw_events=state.get("events", 0),
                transport=kind,
                error=state.get("error"),
                http_status=int(status),
            )
            return int(status), sr, None
    except urllib.error.HTTPError as e:
        body_txt = ""
        try:
            body_txt = e.read().decode("utf-8", errors="replace")[:2000]
        except Exception:
            pass
        msg = redact_secrets(f"HTTP {e.code}: {body_txt}", secrets)
        sr = StreamResult(
            total_ms=_now_ms() - started,
            error=msg,
            http_status=e.code,
            transport=kind,
        )
        return int(e.code), sr, msg
    except Exception as e:
        msg = safe_error_message(e, secrets)
        sr = StreamResult(
            total_ms=_now_ms() - started,
            error=msg,
            transport=kind,
        )
        return -1, sr, msg


def openai_messages_to_gemini_contents(
    messages: Sequence[Dict[str, str]],
) -> Tuple[Optional[Dict[str, Any]], List[Dict[str, Any]]]:
    system_instruction = None
    contents: List[Dict[str, Any]] = []
    for m in messages:
        role = m.get("role")
        text = m.get("content") or ""
        if role == "system":
            system_instruction = {"parts": [{"text": text}]}
            continue
        g_role = "user" if role == "user" else "model"
        contents.append({"role": g_role, "parts": [{"text": text}]})
    return system_instruction, contents


def call_model(
    *,
    provider: str,
    model: str,
    messages: Sequence[Dict[str, str]],
    variant: VariantSpec,
    api_keys: Dict[str, str],
    secrets: Sequence[str],
    pause_s: float,
    sleep_fn: Callable[[float], None] = time.sleep,
) -> CallResult:
    """Execute one streaming completion with retries on 429/503."""
    if not variant.supported:
        return CallResult(status="unsupported", variant_probe=variant.probe_label)

    attempts: List[HttpAttempt] = []
    last: Optional[CallResult] = None

    for attempt_i in range(1 + MAX_RETRIES_TRANSIENT):
        if attempt_i > 0:
            sleep_fn(min(30.0, (2 ** attempt_i) * pause_s))

        if provider == "groq":
            key = api_keys.get("GROQ_API_KEY") or ""
            if not key:
                return CallResult(
                    status="error",
                    attempts=[HttpAttempt(status=None, error="GROQ_API_KEY missing")],
                )
            body: Dict[str, Any] = {
                "model": model,
                "messages": list(messages),
                "stream": True,
                "temperature": 0.3,
                "max_tokens": 256,
            }
            body.update(variant.openai_body_extra)
            status, sr, err = _http_stream(
                GROQ_URL,
                {"Authorization": f"Bearer {key}"},
                body,
                kind="openai",
                secrets=secrets,
            )
            attempts.append(HttpAttempt(status=status, error=err, transport="openai"))
            if status == 400:
                return CallResult(
                    status="unsupported",
                    attempts=attempts,
                    stream=sr,
                    variant_probe=variant.probe_label,
                    transport="openai",
                )
            if status in (429, 503):
                last = CallResult(
                    status="error",
                    attempts=attempts,
                    stream=sr,
                    variant_probe=variant.probe_label,
                    transport="openai",
                )
                continue
            if 200 <= status < 300 and not sr.error:
                return CallResult(
                    status="ok",
                    attempts=attempts,
                    stream=sr,
                    variant_probe=variant.probe_label,
                    transport="openai",
                )
            return CallResult(
                status="error",
                attempts=attempts,
                stream=sr,
                variant_probe=variant.probe_label,
                transport="openai",
            )

        if provider == "gemini":
            key = api_keys.get("GEMINI_API_KEY") or ""
            if not key:
                return CallResult(
                    status="error",
                    attempts=[HttpAttempt(status=None, error="GEMINI_API_KEY missing")],
                )
            # Try OpenAI-compatible first
            body = {
                "model": model,
                "messages": list(messages),
                "stream": True,
                "temperature": 0.3,
                "max_tokens": 256,
            }
            body.update(variant.openai_body_extra)
            status, sr, err = _http_stream(
                GEMINI_OPENAI_URL,
                {"Authorization": f"Bearer {key}"},
                body,
                kind="openai",
                secrets=secrets,
            )
            attempts.append(HttpAttempt(status=status, error=err, transport="openai"))
            if status == 400:
                # try native with same semantic
                sys_inst, contents = openai_messages_to_gemini_contents(messages)
                native_body: Dict[str, Any] = {
                    "contents": contents,
                    "generationConfig": {
                        "temperature": 0.3,
                        "maxOutputTokens": 256,
                    },
                }
                if sys_inst:
                    native_body["systemInstruction"] = sys_inst
                if variant.native_generation_config:
                    native_body["generationConfig"].update(variant.native_generation_config)
                url = GEMINI_NATIVE_TMPL.format(model=model)
                status2, sr2, err2 = _http_stream(
                    url,
                    {"x-goog-api-key": key},
                    native_body,
                    kind="gemini_native",
                    secrets=secrets,
                )
                attempts.append(
                    HttpAttempt(status=status2, error=err2, transport="gemini_native")
                )
                if status2 == 400:
                    return CallResult(
                        status="unsupported",
                        attempts=attempts,
                        stream=sr2,
                        variant_probe=variant.probe_label,
                        transport="gemini_native",
                    )
                if status2 in (429, 503):
                    last = CallResult(
                        status="error",
                        attempts=attempts,
                        stream=sr2,
                        variant_probe=variant.probe_label,
                        transport="gemini_native",
                    )
                    continue
                if 200 <= status2 < 300 and not sr2.error:
                    return CallResult(
                        status="ok",
                        attempts=attempts,
                        stream=sr2,
                        variant_probe=variant.probe_label,
                        transport="gemini_native",
                    )
                return CallResult(
                    status="error",
                    attempts=attempts,
                    stream=sr2,
                    variant_probe=variant.probe_label,
                    transport="gemini_native",
                )

            if status in (429, 503):
                last = CallResult(
                    status="error",
                    attempts=attempts,
                    stream=sr,
                    variant_probe=variant.probe_label,
                    transport="openai",
                )
                continue

            if 200 <= status < 300 and not sr.error:
                return CallResult(
                    status="ok",
                    attempts=attempts,
                    stream=sr,
                    variant_probe=variant.probe_label,
                    transport="openai",
                )

            # Non-400 failure on openai: try native fallback once
            if status not in (429, 503) and status != 400:
                sys_inst, contents = openai_messages_to_gemini_contents(messages)
                native_body = {
                    "contents": contents,
                    "generationConfig": {
                        "temperature": 0.3,
                        "maxOutputTokens": 256,
                    },
                }
                if sys_inst:
                    native_body["systemInstruction"] = sys_inst
                if variant.native_generation_config:
                    native_body["generationConfig"].update(variant.native_generation_config)
                url = GEMINI_NATIVE_TMPL.format(model=model)
                status2, sr2, err2 = _http_stream(
                    url,
                    {"x-goog-api-key": key},
                    native_body,
                    kind="gemini_native",
                    secrets=secrets,
                )
                attempts.append(
                    HttpAttempt(status=status2, error=err2, transport="gemini_native")
                )
                if status2 in (429, 503):
                    last = CallResult(
                        status="error",
                        attempts=attempts,
                        stream=sr2,
                        variant_probe=variant.probe_label,
                        transport="gemini_native",
                    )
                    continue
                if 200 <= status2 < 300 and not sr2.error:
                    return CallResult(
                        status="ok",
                        attempts=attempts,
                        stream=sr2,
                        variant_probe=variant.probe_label,
                        transport="gemini_native",
                    )
                return CallResult(
                    status="error",
                    attempts=attempts,
                    stream=sr2 if sr2.error else sr,
                    variant_probe=variant.probe_label,
                    transport="gemini_native",
                )

            return CallResult(
                status="error",
                attempts=attempts,
                stream=sr,
                variant_probe=variant.probe_label,
                transport="openai",
            )

        return CallResult(
            status="error",
            attempts=[HttpAttempt(status=None, error=f"unknown provider {provider}")],
        )

    return last or CallResult(status="error", attempts=attempts)


def resolve_minimal_variant(
    *,
    provider: str,
    model: str,
    probe_messages: Sequence[Dict[str, str]],
    api_keys: Dict[str, str],
    secrets: Sequence[str],
    dry_run: bool,
) -> Tuple[VariantSpec, List[str]]:
    """Probe which minimal-thinking params the API accepts. Returns (spec, log)."""
    log: List[str] = []
    if provider == "gemini":
        cands = gemini_minimal_candidates()
    elif provider == "groq":
        cands = groq_minimal_candidates(model)
    else:
        return (
            VariantSpec(name="unsupported", supported=False, probe_label="unknown_provider"),
            ["unknown provider"],
        )

    if dry_run:
        first = cands[0]
        log.append(f"dry-run: would probe {[c.probe_label for c in cands]}; assume first")
        return first, log

    for cand in cands:
        log.append(f"probe {cand.probe_label}…")
        res = call_model(
            provider=provider,
            model=model,
            messages=probe_messages,
            variant=cand,
            api_keys=api_keys,
            secrets=secrets,
            pause_s=1.0,
        )
        statuses = [a.status for a in res.attempts]
        log.append(f"  attempts status={statuses} result={res.status}")
        if res.status == "ok":
            log.append(f"  accepted: {cand.probe_label}")
            return cand, log
        if res.status == "unsupported" or (res.attempts and res.attempts[-1].status == 400):
            continue
        # other errors (auth, 404 model) — stop probing this model
        log.append("  non-400 error during probe; marking unsupported for minimal")
        break

    return (
        VariantSpec(
            name="unsupported",
            supported=False,
            probe_label="no_minimal_param_accepted",
        ),
        log,
    )


# ── Reporting ──────────────────────────────────────────────────────


def percentile(values: Sequence[float], p: float) -> Optional[float]:
    if not values:
        return None
    if len(values) == 1:
        return float(values[0])
    ordered = sorted(values)
    k = (len(ordered) - 1) * (p / 100.0)
    f = int(k)
    c = min(f + 1, len(ordered) - 1)
    if f == c:
        return float(ordered[f])
    return float(ordered[f] + (ordered[c] - ordered[f]) * (k - f))


def write_outputs(
    out_dir: Path,
    rows: List[Dict[str, Any]],
    *,
    variant_notes: Dict[str, str],
    token_estimate: Dict[str, Any],
) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    jsonl = out_dir / "results.jsonl"
    with jsonl.open("w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")

    # Aggregate by model+variant
    groups: Dict[Tuple[str, str, str], List[Dict[str, Any]]] = {}
    for r in rows:
        key = (r["provider"], r["model"], r["variant"])
        groups.setdefault(key, []).append(r)

    lines = [
        "# Cloud bench summary",
        "",
        f"Generated: {datetime.now(timezone.utc).isoformat()}",
        "",
        "## Variant probes",
        "",
    ]
    for k, v in sorted(variant_notes.items()):
        lines.append(f"- `{k}`: {v}")
    lines += ["", "## Per model × variant", ""]
    lines.append(
        "| provider | model | variant | n | success% | med TTFT ms | p90 TTFT | "
        "med total ms | avg tokens (in+out+reason) | auto memory | auto command | "
        "auto plan | 429 | 503 | total tokens |"
    )
    lines.append("|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")

    short_lines = [
        "CLOUD BENCH SHORT REPORT",
        f"ts={out_dir.name}",
        "",
    ]

    for (prov, model, variant), items in sorted(groups.items()):
        n = len(items)
        oks = [i for i in items if i.get("status") == "ok"]
        success = 100.0 * len(oks) / n if n else 0.0
        ttfts = [float(i["ttft_ms"]) for i in oks if i.get("ttft_ms") is not None]
        totals = [float(i["total_ms"]) for i in oks if i.get("total_ms") is not None]
        tok_sums = []
        total_tokens_all = 0
        for i in items:
            inn = i.get("in_tokens") or 0
            out = i.get("out_tokens") or 0
            rea = i.get("reasoning_tokens") or 0
            s = inn + out + rea
            total_tokens_all += s
            if i.get("status") == "ok":
                tok_sums.append(s)
        c429 = sum(
            1
            for i in items
            for a in (i.get("attempts") or [])
            if isinstance(a, dict) and a.get("status") == 429
        )
        c503 = sum(
            1
            for i in items
            for a in (i.get("attempts") or [])
            if isinstance(a, dict) and a.get("status") == 503
        )

        def cat_rate(cat: str) -> str:
            scored = [
                i
                for i in items
                if i.get("category") == cat and i.get("auto_pass") is not None
            ]
            if not scored:
                return "—"
            passed = sum(1 for i in scored if i.get("auto_pass") is True)
            return f"{passed}/{len(scored)}"

        med_ttft = statistics.median(ttfts) if ttfts else None
        p90_ttft = percentile(ttfts, 90)
        med_tot = statistics.median(totals) if totals else None
        avg_tok = statistics.mean(tok_sums) if tok_sums else 0.0

        def fmt(x: Optional[float]) -> str:
            return "—" if x is None else f"{x:.0f}"

        lines.append(
            f"| {prov} | {model} | {variant} | {n} | {success:.0f}% | "
            f"{fmt(med_ttft)} | {fmt(p90_ttft)} | {fmt(med_tot)} | "
            f"{avg_tok:.0f} | {cat_rate('memory')} | {cat_rate('command')} | "
            f"{cat_rate('plan')} | {c429} | {c503} | {total_tokens_all} |"
        )
        short_lines.append(
            f"{prov}/{model} [{variant}]: ok={len(oks)}/{n} "
            f"med_ttft_ms={fmt(med_ttft)} med_total_ms={fmt(med_tot)} "
            f"avg_tok={avg_tok:.0f} mem={cat_rate('memory')} "
            f"cmd={cat_rate('command')} plan={cat_rate('plan')} "
            f"429={c429} 503={c503}"
        )

    lines += ["", "## Token estimate (pre-run)", "", "```json", json.dumps(token_estimate, indent=2), "```", ""]
    (out_dir / "summary.md").write_text("\n".join(lines), encoding="utf-8")
    short_lines += ["", "Send to Claude: this file + results.jsonl (answers included there)."]
    (out_dir / "short_report.txt").write_text("\n".join(short_lines) + "\n", encoding="utf-8")


# ── CLI ────────────────────────────────────────────────────────────


def load_json(path: Path) -> Any:
    with path.open(encoding="utf-8") as f:
        return json.load(f)


def filter_models(
    models: List[Dict[str, Any]],
    filt: Optional[str],
) -> List[Dict[str, Any]]:
    if not filt:
        return models
    wanted = {x.strip() for x in filt.split(",") if x.strip()}
    out = []
    for m in models:
        if m["model"] in wanted or m.get("label") in wanted or m["provider"] in wanted:
            out.append(m)
    return out


def estimate_run_tokens(
    models: Sequence[Dict[str, Any]],
    prompts: Sequence[Dict[str, Any]],
    variants: Sequence[str],
    prompt_data: Dict[str, Any],
) -> Dict[str, Any]:
    per_provider: Dict[str, int] = {}
    details = []
    system = prompt_data["system"]
    facts = prompt_data["memory_facts"]
    tools = prompt_data["tools"]
    for m in models:
        for vname in variants:
            if vname == "minimal-thinking":
                mult = 1.0
            else:
                mult = THINKING_MULTIPLIER
            for p in prompts:
                msgs = build_messages(
                    p, system=system, memory_facts=facts, tools=tools
                )
                inn = estimate_prompt_tokens(msgs)
                # assume ~80 out + possible reasoning
                out_est = 80
                total = int((inn + out_est) * mult)
                per_provider[m["provider"]] = per_provider.get(m["provider"], 0) + total
                details.append(
                    {
                        "provider": m["provider"],
                        "model": m["model"],
                        "variant": vname,
                        "prompt_id": p["id"],
                        "est_tokens": total,
                    }
                )
    n_calls = len(models) * len(variants) * len(prompts)
    return {
        "calls": n_calls,
        "per_provider_est_tokens": per_provider,
        "total_est_tokens": sum(per_provider.values()),
        "note": (
            f"Conservative: default variant ×{THINKING_MULTIPLIER} for thinking; "
            "minimal-thinking ×1. Not a billing guarantee."
        ),
        "sample": details[:3],
    }


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Jarvis cloud LLM bench (stdlib)")
    p.add_argument("--models-file", type=Path, default=DEFAULT_MODELS)
    p.add_argument("--prompts-file", type=Path, default=DEFAULT_PROMPTS)
    p.add_argument("--models", type=str, default="", help="Filter: comma model ids/labels/providers")
    p.add_argument(
        "--variants",
        type=str,
        default="default,minimal-thinking",
        help="Comma list: default,minimal-thinking",
    )
    p.add_argument("--limit-prompts", type=int, default=0)
    p.add_argument("--out", type=Path, default=HERE / "results")
    p.add_argument("--pause", type=float, default=DEFAULT_PAUSE_S)
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--yes", action="store_true", help="Skip confirmation")
    return p.parse_args(argv)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    secrets = collect_secrets()
    api_keys = {
        "GEMINI_API_KEY": (os.environ.get("GEMINI_API_KEY") or "").strip(),
        "GROQ_API_KEY": (os.environ.get("GROQ_API_KEY") or "").strip(),
        "MISTRAL_API_KEY": (os.environ.get("MISTRAL_API_KEY") or "").strip(),
    }

    models = filter_models(load_json(args.models_file), args.models or None)
    prompt_data = load_json(args.prompts_file)
    prompts: List[Dict[str, Any]] = list(prompt_data["prompts"])
    if args.limit_prompts and args.limit_prompts > 0:
        prompts = prompts[: args.limit_prompts]
    variants = [v.strip() for v in args.variants.split(",") if v.strip()]

    if not models:
        print("No models selected.", file=sys.stderr)
        return 2
    if not prompts:
        print("No prompts selected.", file=sys.stderr)
        return 2

    # Missing keys warning (never print values)
    for m in models:
        if m["provider"] == "gemini" and not api_keys["GEMINI_API_KEY"] and not args.dry_run:
            print("WARNING: GEMINI_API_KEY not set; gemini calls will fail.", file=sys.stderr)
            break
    for m in models:
        if m["provider"] == "groq" and not api_keys["GROQ_API_KEY"] and not args.dry_run:
            print("WARNING: GROQ_API_KEY not set; groq calls will fail.", file=sys.stderr)
            break

    estimate = estimate_run_tokens(models, prompts, variants, prompt_data)
    print("=== Cloud bench plan ===")
    print(f"models ({len(models)}): " + ", ".join(f"{m['provider']}/{m['model']}" for m in models))
    print(f"variants: {variants}")
    print(f"prompts: {len(prompts)}")
    print(f"calls: {estimate['calls']}")
    print(f"est tokens by provider: {estimate['per_provider_est_tokens']}")
    print(f"est tokens total (conservative): {estimate['total_est_tokens']}")
    print(estimate["note"])
    pause = args.pause
    # per-provider pause note
    print(f"pause between requests: {pause}s (same for each provider by default)")

    if args.dry_run:
        print("--- dry-run: no HTTP requests ---")
        for m in models:
            for v in variants:
                for pr in prompts:
                    print(f"PLAN {m['provider']}/{m['model']} [{v}] {pr['id']} ({pr['category']})")
        print("Dry-run complete.")
        return 0

    if not args.yes:
        try:
            ans = input("Proceed with live API calls? [y/N] ").strip().lower()
        except EOFError:
            ans = "n"
        if ans not in ("y", "yes"):
            print("Aborted.")
            return 1

    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = args.out / ts
    out_dir.mkdir(parents=True, exist_ok=True)

    system = prompt_data["system"]
    facts = prompt_data["memory_facts"]
    tools = prompt_data["tools"]
    probe_messages = [
        {"role": "system", "content": system},
        {"role": "user", "content": "Ответь одним словом: ок"},
    ]

    resolved_minimal: Dict[str, VariantSpec] = {}
    variant_notes: Dict[str, str] = {}

    rows: List[Dict[str, Any]] = []
    provider_last: Dict[str, float] = {}

    for m in models:
        mid = f"{m['provider']}/{m['model']}"
        for vname in variants:
            if vname == "default":
                variant = default_variant()
                variant_notes[f"{mid}|default"] = "omit special reasoning params"
            elif vname == "minimal-thinking":
                if mid not in resolved_minimal:
                    spec, log = resolve_minimal_variant(
                        provider=m["provider"],
                        model=m["model"],
                        probe_messages=probe_messages,
                        api_keys=api_keys,
                        secrets=secrets,
                        dry_run=False,
                    )
                    resolved_minimal[mid] = spec
                    variant_notes[f"{mid}|minimal-thinking"] = (
                        f"{spec.probe_label}; " + " | ".join(log)
                    )
                    print(redact_secrets(f"minimal probe {mid}: {spec.probe_label} supported={spec.supported}"))
                variant = resolved_minimal[mid]
            else:
                print(f"Unknown variant {vname}, skip", file=sys.stderr)
                continue

            for pr in prompts:
                # rate limit pause per provider
                prov = m["provider"]
                last = provider_last.get(prov)
                if last is not None:
                    elapsed = time.time() - last
                    if elapsed < pause:
                        time.sleep(pause - elapsed)

                messages = build_messages(
                    pr, system=system, memory_facts=facts, tools=tools
                )
                print(f"RUN {mid} [{variant.name}] {pr['id']}…", flush=True)
                if variant.name == "unsupported" or not variant.supported:
                    row = {
                        "prompt_id": pr["id"],
                        "category": pr["category"],
                        "provider": m["provider"],
                        "model": m["model"],
                        "label": m.get("label"),
                        "variant": "unsupported",
                        "status": "unsupported",
                        "attempts": [],
                        "ttft_ms": None,
                        "total_ms": None,
                        "in_tokens": None,
                        "out_tokens": None,
                        "reasoning_tokens": None,
                        "auto_pass": None,
                        "has_markdown": None,
                        "text_len": 0,
                        "text": "",
                        "variant_probe": variant.probe_label,
                        "transport": None,
                    }
                    rows.append(row)
                    provider_last[prov] = time.time()
                    continue

                res = call_model(
                    provider=m["provider"],
                    model=m["model"],
                    messages=messages,
                    variant=variant,
                    api_keys=api_keys,
                    secrets=secrets,
                    pause_s=pause,
                )
                provider_last[prov] = time.time()
                text = res.stream.text or ""
                auto = (
                    auto_score(pr["category"], text, pr, tools)
                    if res.status == "ok"
                    else None
                )
                row = {
                    "prompt_id": pr["id"],
                    "category": pr["category"],
                    "provider": m["provider"],
                    "model": m["model"],
                    "label": m.get("label"),
                    "variant": variant.name if variant.supported else "unsupported",
                    "status": res.status,
                    "attempts": [asdict(a) for a in res.attempts],
                    "ttft_ms": res.stream.ttft_ms,
                    "total_ms": res.stream.total_ms,
                    "in_tokens": res.stream.in_tokens,
                    "out_tokens": res.stream.out_tokens,
                    "reasoning_tokens": res.stream.reasoning_tokens,
                    "auto_pass": auto,
                    "has_markdown": has_markdown(text) if text else None,
                    "text_len": len(text),
                    "text": text,
                    "variant_probe": res.variant_probe or variant.probe_label,
                    "transport": res.transport,
                    "error": redact_secrets(res.stream.error or "", secrets) or None,
                }
                rows.append(row)

    write_outputs(out_dir, rows, variant_notes=variant_notes, token_estimate=estimate)
    print(f"Wrote {out_dir}")
    print(f"short_report: {out_dir / 'short_report.txt'}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as e:
        print(safe_error_message(e), file=sys.stderr)
        raise SystemExit(1)
