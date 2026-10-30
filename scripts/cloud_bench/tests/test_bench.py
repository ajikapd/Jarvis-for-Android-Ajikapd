# -*- coding: utf-8 -*-
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, ROOT)
sys.path.insert(0, HERE)

import bench  # noqa: E402
import mock_server  # noqa: E402

FAKE_KEY = "test-secret-key-12345678"
os.environ.setdefault("NO_PROXY", "127.0.0.1,localhost")
os.environ.setdefault("no_proxy", "127.0.0.1,localhost")


class Base(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv, cls.base = mock_server.start()

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.srv.server_close()

    def setUp(self):
        mock_server.STATE.requests.clear()
        mock_server.STATE.counters.clear()
        self.env = mock.patch.dict(os.environ, {
            "GROQ_API_KEY": FAKE_KEY, "GEMINI_API_KEY": FAKE_KEY,
            "BENCH_GROQ_BASE": self.base, "BENCH_GEMINI_BASE": self.base,
        })
        self.env.start()
        os.environ.pop("BENCH_USER_AGENT", None)

    def tearDown(self):
        self.env.stop()

    def call(self, model, provider="groq", extra=None):
        return bench.stream_chat(provider, model, [{"role": "user", "content": "привет"}], extra, timeout=10)


class TestTransport(Base):
    def test_user_agent_is_not_python_urllib_and_env_override(self):
        r = self.call("ok-model")
        self.assertTrue(r["ok"], r)
        ua = mock_server.STATE.requests[-1]["ua"]
        self.assertNotIn("python-urllib", ua.lower())
        os.environ["BENCH_USER_AGENT"] = "custom-agent/9"
        self.call("ok-model")
        self.assertEqual(mock_server.STATE.requests[-1]["ua"], "custom-agent/9")

    def test_default_urllib_ua_would_be_blocked_by_gate(self):
        os.environ["BENCH_USER_AGENT"] = "Python-urllib/3.12"
        r = self.call("ok-model")
        self.assertFalse(r["ok"])
        self.assertEqual(bench.classify_error(r["http"], r["error"]), "blocked_by_cloudflare_user_agent_or_region")

    def test_usage_and_chunks(self):
        r = self.call("ok-model")
        self.assertEqual((r["in_tokens"], r["out_tokens"], r["reasoning_tokens"], r["total_tokens"]),
                         (100, 20, 5, 125))
        self.assertGreaterEqual(r["chunks"], 3)
        self.assertIsNotNone(r["t_first_chunk"])
        self.assertIsNotNone(r["t_first_visible"])
        self.assertLessEqual(r["t_first_chunk"], r["t_last_chunk"])

    def test_missing_usage_is_none_not_zero(self):
        r = self.call("nousage-model")
        self.assertTrue(r["ok"])
        self.assertIsNone(r["in_tokens"])
        self.assertIsNone(r["out_tokens"])
        self.assertIsNone(r["reasoning_tokens"])

    def test_non_stream_json_response(self):
        r = self.call("nostream-model")
        self.assertTrue(r["ok"])
        self.assertEqual(r["text"], "Привет!")
        self.assertEqual(r["chunks"], 1)
        self.assertEqual(r["in_tokens"], 10)

    def test_thought_leak_stripped_and_first_visible_after_thought(self):
        r = self.call("thought-model")
        self.assertTrue(r["ok"])
        self.assertEqual(r["text"], "Привет, сэр")
        self.assertTrue(r["thought_leak"])
        self.assertGreater(r["thought_chars"], 0)

    def test_extra_params_are_sent(self):
        self.call("ok-model", extra={"reasoning_effort": "low", "include_reasoning": False})
        body = mock_server.STATE.requests[-1]["body"]
        self.assertEqual(body["reasoning_effort"], "low")
        self.assertIs(body["include_reasoning"], False)
        self.assertTrue(body["stream_options"]["include_usage"])

    def test_fallback_without_stream_options(self):
        r = self.call("strict-model")
        self.assertTrue(r["ok"], r)
        self.assertIsNone(r["in_tokens"])          # usage недоступен, но запрос прошёл
        self.assertEqual(len(mock_server.STATE.requests), 2)
        self.assertNotIn("stream_options", mock_server.STATE.requests[-1]["body"])

    def test_fallback_to_max_completion_tokens(self):
        r = self.call("maxtok-model")
        self.assertTrue(r["ok"], r)
        last = mock_server.STATE.requests[-1]["body"]
        self.assertIn("max_completion_tokens", last)
        self.assertNotIn("max_tokens", last)

    def test_key_never_leaks_in_errors(self):
        r = self.call("auth-model")
        self.assertNotIn(FAKE_KEY, json.dumps(r))
        self.assertEqual(bench.redact("Authorization: Bearer %s" % FAKE_KEY).count(FAKE_KEY), 0)


class TestClassify(unittest.TestCase):
    def test_table(self):
        c = bench.classify_error
        self.assertEqual(c(403, "error code: 1010"), "blocked_by_cloudflare_user_agent_or_region")
        self.assertEqual(c(403, "forbidden"), "auth_or_region")
        self.assertEqual(c(401, ""), "auth_or_region")
        self.assertEqual(c(429, "GenerateRequestsPerDayPerProjectPerModel"), "rate_limited_daily")
        self.assertEqual(c(429, "Rate limit reached ... tokens per day (TPD)"), "rate_limited_daily")
        self.assertEqual(c(429, "slow down per minute"), "rate_limited")
        self.assertEqual(c(503, "high demand"), "overloaded")
        self.assertEqual(c(None, "URLError: Connection refused"), "network_error")
        self.assertEqual(c(None, "missing_key", has_key=False), "missing_key")
        self.assertEqual(c(400, "bad"), "bad_request")

    def test_retry_after_parsing(self):
        self.assertEqual(bench.retry_after({"headers": {"retry-after": "7"}}), 7.0)
        self.assertEqual(bench.retry_after({"error": '"retryDelay": "21s"'}), 21.0)
        self.assertAlmostEqual(bench.retry_after({"error": "Please try again in 250ms"}), 0.25)
        self.assertIsNone(bench.retry_after({"error": "nothing"}))


class TestHelpers(unittest.TestCase):
    def test_split_thoughts(self):
        clean, th = bench.split_thoughts("<thought>x</thought>Ответ")
        self.assertEqual(clean, "Ответ")
        self.assertEqual(len(th), 1)
        clean, th = bench.split_thoughts("Ответ<think>не закрыто")
        self.assertEqual(clean, "Ответ")
        self.assertEqual(len(th), 1)

    def test_extract_json_and_scores(self):
        names = ["flashlight", "weather", "time_now"]
        self.assertEqual(bench.extract_json('```json\n{"a": 1}\n```'), {"a": 1})
        self.assertEqual(bench.extract_json('Вот: {"tool":"x","args":{"q":"}"}} конец'), {"tool": "x", "args": {"q": "}"}})
        p = {"category": "command", "expect_tool": "flashlight"}
        self.assertTrue(bench.score(p, '{"tool":"flashlight","args":{"on":true}}', names))
        self.assertFalse(bench.score(p, '{"tool":"weather","args":{}}', names))
        self.assertFalse(bench.score(p, "включаю", names))
        pl = {"category": "plan"}
        self.assertTrue(bench.score(pl, '{"steps":[{"tool":"weather","args":{}},{"tool":"time_now","args":{}}]}', names))
        self.assertFalse(bench.score(pl, '{"steps":[]}', names))
        self.assertFalse(bench.score(pl, '{"steps":[{"tool":"nope","args":{}}]}', names))
        mem = {"category": "memory", "expect_any": ["казан"]}
        self.assertTrue(bench.score(mem, "Вы из Казани", names))
        self.assertFalse(bench.score(mem, "Не знаю", names))
        self.assertIsNone(bench.score({"category": "chat"}, "привет", names))

    def test_markdown_detection(self):
        self.assertTrue(bench.has_markdown("```json\n{}\n```"))
        self.assertTrue(bench.has_markdown("- пункт"))
        self.assertFalse(bench.has_markdown("Обычный текст."))

    def test_select_models(self):
        ms = _read_json(os.path.join(ROOT, "models.json"))
        default = bench.select_models(ms, "", "", False, False)
        ids = [m["model"] for m in default]
        self.assertIn("gemini-3.5-flash-lite", ids)
        self.assertNotIn("gemini-3.8-flash", ids)       # heavy
        self.assertNotIn("gemma-4-26b-a4b-it", ids)     # experimental
        groq = bench.select_models(ms, "groq", "", False, False)
        self.assertTrue(groq and all(m["provider"] == "groq" for m in groq))
        both = bench.select_models(ms, "groq", "gpt-oss-20b", False, False)
        self.assertEqual([m["model"] for m in both], ["openai/gpt-oss-20b"])
        explicit = bench.select_models(ms, "", "gemini-3.8-flash", False, False)
        self.assertEqual([m["model"] for m in explicit], ["gemini-3.8-flash"])
        heavy = bench.select_models(ms, "gemini", "", True, False)
        self.assertIn("gemini-3.8-flash", [m["model"] for m in heavy])


def _read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def _read_json(path):
    return json.loads(_read(path))


def _read_rows(path):
    return [json.loads(x) for x in _read(path).splitlines() if x.strip()]


def _models_file(tmp, models):
    p = os.path.join(tmp, "models.json")
    with open(p, "w", encoding="utf-8") as f:
        json.dump(models, f)
    return p


def _args(**kw):
    base = bench.parse_args(["--pause", "0", "--retry-sleep", "0", "--timeout", "10"])
    for k, v in kw.items():
        setattr(base, k, v)
    return base


class TestPreflightAndRun(Base):
    def test_preflight_missing_key_does_not_crash(self):
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("GROQ_API_KEY", None)
            pf = bench.preflight_model({"provider": "groq", "model": "ok-model"}, True, _args())
        self.assertEqual(pf["status"], "missing_key")

    def test_preflight_classifies_and_probes_min(self):
        a = _args()
        self.assertEqual(bench.preflight_model({"provider": "groq", "model": "ok-model"}, False, a)["status"], "ok")
        self.assertEqual(bench.preflight_model({"provider": "groq", "model": "overload-model"}, False, a)["status"], "overloaded")
        self.assertEqual(bench.preflight_model({"provider": "groq", "model": "daily-model"}, False, a)["status"], "rate_limited_daily")
        pf = bench.preflight_model({"provider": "groq", "model": "min-picky",
                                    "min_candidates": [{"reasoning_effort": "none"}, {"reasoning_effort": "minimal"}]}, True, a)
        self.assertEqual(pf["status"], "ok")
        self.assertEqual(pf["min_params"], {"reasoning_effort": "minimal"})   # none -> 400, minimal принят
        pf2 = bench.preflight_model({"provider": "groq", "model": "min-picky",
                                     "min_candidates": [{"reasoning_effort": "none"}]}, True, a)
        self.assertEqual(pf2["min_status"], "unsupported")

    def test_preflight_single_attempt_no_retries(self):
        mock_server.STATE.counters.clear()
        bench.preflight_model({"provider": "groq", "model": "overload-model"}, False, _args())
        self.assertEqual(mock_server.STATE.counters["overload-model"], 1)

    def _run(self, models, extra_args=None, env=None):
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp, True)
        mf = _models_file(tmp, models)
        cmd = [sys.executable, os.path.join(ROOT, "bench.py"), "--models-file", mf, "--out", tmp,
               "--pause", "0", "--retry-sleep", "0", "--yes", "--workers", "3"] + (extra_args or [])
        e = dict(os.environ)
        e.update(env or {})
        p = subprocess.run(cmd, capture_output=True, text=True, env=e, timeout=120)
        runs = [d for d in os.listdir(tmp) if os.path.isdir(os.path.join(tmp, d))]
        return p, tmp, runs

    def test_end_to_end_smart_model(self):
        models = [{"provider": "groq", "model": "smart-model", "label": "smart",
                   "min_candidates": [{"reasoning_effort": "low"}]}]
        p, tmp, runs = self._run(models, ["--variants", "default,min"])
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertNotIn(FAKE_KEY, p.stdout + p.stderr)
        d = os.path.join(tmp, runs[0])
        rows = _read_rows(os.path.join(d, "results.jsonl"))
        self.assertEqual(len(rows), 60)   # 30 фраз x 2 варианта
        self.assertTrue(all(r["status"] == "ok" for r in rows))
        for cat in ("memory", "command", "plan"):
            self.assertTrue(all(r["auto_pass"] for r in rows if r["category"] == cat), cat)
        allout = "".join(_read(os.path.join(d, f))
                         for f in ("results.jsonl", "summary.md", "short_report.txt", "run_meta.json"))
        self.assertNotIn(FAKE_KEY, allout)
        short = _read(os.path.join(d, "short_report.txt"))
        self.assertIn("groq/smart-model [default]", short)
        self.assertIn("groq/smart-model [min]", short)
        self.assertIn("mem=5/5 cmd=5/5 plan=5/5", short)

    def test_missing_usage_shows_na_in_summary(self):
        models = [{"provider": "groq", "model": "nousage-model", "label": "x", "min_candidates": []}]
        p, tmp, runs = self._run(models, ["--variants", "default", "--limit-prompts", "3"])
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        short = _read(os.path.join(tmp, runs[0], "short_report.txt"))
        self.assertIn("tok(in/out/reason/total)=n/a/n/a/n/a/n/a", short)

    def test_no_stream_flag(self):
        models = [{"provider": "groq", "model": "nostream-model", "label": "x", "min_candidates": []}]
        p, tmp, runs = self._run(models, ["--variants", "default", "--limit-prompts", "3"])
        short = _read(os.path.join(tmp, runs[0], "short_report.txt"))
        self.assertIn("NO-STREAM", short)

    def test_daily_quota_and_circuit_breaker(self):
        models = [{"provider": "groq", "model": "daily-model", "label": "d", "min_candidates": []},
                  {"provider": "groq", "model": "overload-model", "label": "o", "min_candidates": []}]
        # без preflight, чтобы проверить именно защиту во время прогона
        p, tmp, runs = self._run(models, ["--variants", "default", "--no-preflight"])
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        meta = _read_json(os.path.join(tmp, runs[0], "run_meta.json"))
        self.assertEqual(meta["models"]["groq/daily-model"]["status"], "daily_quota_exhausted")
        self.assertEqual(meta["models"]["groq/overload-model"]["status"], "circuit_open")
        self.assertGreater(meta["models"]["groq/daily-model"]["skipped"], 20)
        self.assertEqual(mock_server.STATE.counters["daily-model"], 1)       # не долбим впустую
        self.assertLessEqual(mock_server.STATE.counters["overload-model"], 3 * 3)  # 3 фразы x (1+2 повтора)

    def test_preflight_failure_skips_model(self):
        models = [{"provider": "groq", "model": "overload-model", "label": "o", "min_candidates": []},
                  {"provider": "groq", "model": "ok-model", "label": "k", "min_candidates": []}]
        p, tmp, runs = self._run(models, ["--variants", "default", "--limit-prompts", "2"])
        self.assertIn("SKIP groq/overload-model", p.stdout)
        meta = _read_json(os.path.join(tmp, runs[0], "run_meta.json"))
        self.assertTrue(meta["models"]["groq/overload-model"]["status"].startswith("preflight_failed"))
        self.assertEqual(meta["models"]["groq/ok-model"]["status"], "ok")

    def test_preflight_cli_with_empty_keys(self):
        models = [{"provider": "groq", "model": "x", "min_candidates": []}]
        env = {"GROQ_API_KEY": "", "GEMINI_API_KEY": ""}
        p, tmp, runs = self._run(models, ["--preflight"], env)
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertIn("missing_key", p.stdout)
        self.assertNotIn("Traceback", p.stdout + p.stderr)

    def test_rate_limit_retry_then_ok(self):
        models = [{"provider": "groq", "model": "rate-model", "label": "r", "min_candidates": []}]
        p, tmp, runs = self._run(models, ["--variants", "default", "--limit-prompts", "1", "--no-preflight"])
        rows = _read_rows(os.path.join(tmp, runs[0], "results.jsonl"))
        self.assertEqual(rows[0]["status"], "ok")
        self.assertEqual(rows[0]["attempt_log"], ["rate_limited", "ok"])

    def test_dry_run_filters_providers_and_sends_nothing(self):
        p = subprocess.run([sys.executable, os.path.join(ROOT, "bench.py"), "--providers", "groq", "--dry-run"],
                           capture_output=True, text=True, timeout=30)
        self.assertEqual(p.returncode, 0, p.stderr)
        plans = [l for l in p.stdout.splitlines() if "PLAN " in l]
        self.assertTrue(plans)
        self.assertTrue(all("groq/" in l for l in plans), p.stdout)
        self.assertNotIn("gemini/", p.stdout.replace("gemini-", ""))
        self.assertEqual(len(mock_server.STATE.requests), 0)

    def test_merge_later_wins(self):
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp, True)

        def mk(name, status, ttft):
            d = os.path.join(tmp, name)
            os.makedirs(d)
            row = {"provider": "groq", "model": "m", "variant": "default", "prompt_id": "chat_01",
                   "category": "chat", "status": status, "ttft_visible_ms": ttft, "ttft_first_chunk_ms": ttft,
                   "total_ms": ttft, "chunks": 3, "attempt_log": [status]}
            with open(os.path.join(d, "results.jsonl"), "w", encoding="utf-8") as f:
                f.write(json.dumps(row) + "\n")
            with open(os.path.join(d, "run_meta.json"), "w", encoding="utf-8") as f:
                json.dump({"models": {"groq/m": {"status": "ok"}}}, f)
            return d
        a, b = mk("a", "overloaded", 0), mk("b", "ok", 777)
        out = os.path.join(tmp, "merged")
        bench.merge_dirs([a, b], out)
        rows = _read_rows(os.path.join(out, "results.jsonl"))
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["status"], "ok")
        self.assertIn("777", _read(os.path.join(out, "short_report.txt")))


if __name__ == "__main__":
    unittest.main()
