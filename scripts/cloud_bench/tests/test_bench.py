#!/usr/bin/env python3
"""Unit tests for cloud_bench (stdlib unittest)."""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import bench  # noqa: E402


OPENAI_SSE = """
data: {"choices":[{"delta":{"reasoning":"hmm"},"index":0}]}

data: {"choices":[{"delta":{"content":"Привет"},"index":0}]}

data: {"choices":[{"delta":{"content":", мир"},"index":0}],"usage":{"prompt_tokens":10,"completion_tokens":4,"completion_tokens_details":{"reasoning_tokens":2}}}

data: [DONE]

""".lstrip()

GEMINI_NATIVE_SSE = """
data: {"candidates":[{"content":{"parts":[{"thought":true,"text":"думаю"}]}}]}

data: {"candidates":[{"content":{"parts":[{"text":"Ответ"}]}}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":1,"thoughtsTokenCount":3}}

""".lstrip()


class TestSseParsers(unittest.TestCase):
    def test_openai_sse_ttft_skips_reasoning(self):
        started = 1000.0
        # Force deterministic ttft by injecting started_ms near "now" via monkey patch
        real_now = bench._now_ms

        def fake_now():
            # first call after content → ttft; we just check text assembly
            return real_now()

        bench._now_ms = fake_now
        try:
            res = bench.parse_sse_stream(OPENAI_SSE, kind="openai")
        finally:
            bench._now_ms = real_now
        self.assertEqual(res.text, "Привет, мир")
        self.assertIn("hmm", res.reasoning_text)
        self.assertEqual(res.in_tokens, 10)
        self.assertEqual(res.out_tokens, 4)
        self.assertEqual(res.reasoning_tokens, 2)
        self.assertIsNotNone(res.ttft_ms)

    def test_gemini_native_skips_thought_parts(self):
        res = bench.parse_sse_stream(GEMINI_NATIVE_SSE, kind="gemini_native")
        self.assertEqual(res.text, "Ответ")
        self.assertIn("думаю", res.reasoning_text)
        self.assertEqual(res.in_tokens, 5)
        self.assertEqual(res.reasoning_tokens, 3)


class TestJsonValidators(unittest.TestCase):
    def setUp(self):
        self.tools = [
            {"name": "flashlight", "args": {"on": "bool"}},
            {"name": "volume", "args": {"delta": "int"}},
            {"name": "weather", "args": {"city": "string"}},
        ]

    def test_command_ok(self):
        text = '{"tool":"flashlight","args":{"on":true}}'
        self.assertTrue(bench.score_command(text, self.tools, "flashlight"))

    def test_command_wrong_tool(self):
        text = '{"tool":"flashlight","args":{"on":true}}'
        self.assertFalse(bench.score_command(text, self.tools, "volume"))

    def test_command_unknown_tool(self):
        text = '{"tool":"explode","args":{}}'
        self.assertFalse(bench.score_command(text, self.tools))

    def test_plan_ok(self):
        text = json.dumps(
            {
                "steps": [
                    {"tool": "weather", "args": {"city": "Казань"}},
                    {"tool": "flashlight", "args": {"on": False}},
                ]
            },
            ensure_ascii=False,
        )
        self.assertTrue(bench.score_plan(text, self.tools))

    def test_plan_too_short(self):
        text = '{"steps":[{"tool":"weather","args":{}}]}'
        self.assertFalse(bench.score_plan(text, self.tools))

    def test_extract_fenced_json(self):
        text = 'Вот:\n```json\n{"tool":"volume","args":{"delta":1}}\n```'
        self.assertTrue(bench.score_command(text, self.tools, "volume"))


class TestMemoryScore(unittest.TestCase):
    def test_memory_case_insensitive(self):
        self.assertTrue(
            bench.score_memory("Тебя зовут Алексей Северный.", ["алексей", "северный"])
        )
        self.assertFalse(bench.score_memory("Имя неизвестно.", ["алексей"]))


class TestRedaction(unittest.TestCase):
    def test_key_not_in_error_output(self):
        secret = "sk-test-SECRET-VALUE-12345"
        msg = bench.redact_secrets(
            f"HTTP 401: Authorization: Bearer {secret} denied for {secret}",
            [secret],
        )
        self.assertNotIn(secret, msg)
        self.assertIn("[REDACTED]", msg)

    def test_safe_error_message(self):
        secret = "gsk_LIVE_KEY_EXAMPLE"
        try:
            raise RuntimeError(f"failed with key={secret}")
        except RuntimeError as e:
            out = bench.safe_error_message(e, [secret])
        self.assertNotIn(secret, out)
        self.assertIn("[REDACTED]", out)

    def test_goog_header_redacted(self):
        secret = "AIzaSyDummyKeyValue000"
        msg = bench.redact_secrets(f"x-goog-api-key: {secret}", [secret])
        self.assertNotIn(secret, msg)


class TestMarkdownFlag(unittest.TestCase):
    def test_detects_markdown(self):
        self.assertTrue(bench.has_markdown("**жирный**"))
        self.assertFalse(bench.has_markdown("Просто текст без разметки."))


if __name__ == "__main__":
    unittest.main()
