# -*- coding: utf-8 -*-
"""Локальная заглушка OpenAI-совместимого API для тестов (никаких реальных запросов)."""
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class State:
    def __init__(self):
        self.lock = threading.Lock()
        self.requests = []      # {"ua":..., "auth":..., "body":...}
        self.counters = {}


STATE = State()


def _user_text(body):
    for m in reversed(body.get("messages", [])):
        if m.get("role") == "user":
            return m.get("content", "")
    return ""


def _smart_answer(body):
    msgs = body.get("messages", [])
    system = msgs[0]["content"] if msgs else ""
    user = _user_text(body).lower()
    if "[Память]\n- " in system:
        return "Вас зовут Алексей Северный, вы любите зелёный чай и живёте в Казани. Да, верно."
    if '"steps"' in system:
        return '{"steps":[{"tool":"weather","args":{"city":"Казань"}},{"tool":"time_now","args":{}}]}'
    if '"tool"' in system:
        for kw, tool, args in [("фонарик", "flashlight", {"on": True}), ("громче", "volume", {"delta": 2}),
                               ("погода", "weather", {"city": "Казань"}), ("таймер", "timer", {"seconds": 300}),
                               ("настройки", "launcher", {"app": "настройки"})]:
            if kw in user:
                return json.dumps({"tool": tool, "args": args}, ensure_ascii=False)
        return '{"tool":"time_now","args":{}}'
    return "Слушаю вас, сэр."


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def log_message(self, *a):  # тишина
        pass

    def _send(self, code, body, ctype="application/json", headers=None):
        data = body if isinstance(body, bytes) else body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(n).decode("utf-8"))
        ua = self.headers.get("User-Agent", "")
        with STATE.lock:
            STATE.requests.append({"ua": ua, "auth": self.headers.get("Authorization", ""), "body": body})
            model = body.get("model", "")
            STATE.counters[model] = STATE.counters.get(model, 0) + 1
            count = STATE.counters[model]
        if "python-urllib" in ua.lower():
            return self._send(403, "error code: 1010", "text/plain")
        if model == "daily-model":
            return self._send(429, json.dumps({"error": {"message": "quota exceeded",
                              "details": [{"quotaId": "GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}}))
        if model == "rate-model":
            if count == 1:
                return self._send(429, '{"error":{"message":"slow down per minute","details":[{"retryDelay":"0s"}]}}')
        if model == "overload-model":
            return self._send(503, '{"error":{"message":"high demand"}}')
        if model == "strict-model" and "stream_options" in body:
            return self._send(400, '{"error":{"message":"Unknown field: stream_options"}}')
        if model == "maxtok-model" and "max_tokens" in body:
            return self._send(400, '{"error":{"message":"max_tokens is not supported, use max_completion_tokens"}}')
        if model == "auth-model":
            return self._send(401, '{"error":"bad key"}')
        if model == "min-picky" and "reasoning_effort" in body and body["reasoning_effort"] != "minimal":
            return self._send(400, '{"error":"unsupported reasoning_effort"}')
        if model == "nostream-model":
            obj = {"choices": [{"message": {"content": "Привет!"}, "finish_reason": "stop"}],
                   "usage": {"prompt_tokens": 10, "completion_tokens": 3, "total_tokens": 13}}
            return self._send(200, json.dumps(obj, ensure_ascii=False))
        if model == "thought-model":
            parts = ["<thou", "ght>думаю</thought>", "Привет", ", сэр"]
        elif model == "empty-model":
            parts = []
        else:
            parts = [_smart_answer(body)]
            if model != "oneshot-model":
                t = parts[0]
                parts = [t[:len(t) // 2], t[len(t) // 2:]]
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        w = self.wfile
        for p in parts:
            ch = {"choices": [{"index": 0, "delta": {"content": p}}]}
            w.write(("data: %s\n\n" % json.dumps(ch, ensure_ascii=False)).encode("utf-8"))
            w.flush()
        w.write(('data: %s\n\n' % json.dumps({"choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]})).encode())
        if model != "nousage-model" and body.get("stream_options"):
            usage = {"choices": [], "usage": {"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 125,
                                              "completion_tokens_details": {"reasoning_tokens": 5}}}
            w.write(("data: %s\n\n" % json.dumps(usage)).encode())
        w.write(b"data: [DONE]\n\n")
        w.flush()


def start():
    srv = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    return srv, "http://127.0.0.1:%d/v1" % srv.server_address[1]
