from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from src.core.config import Config, clear_config_cache
from src.core.exceptions import LLMRequestError
from src.core.llm_client import LLMClient
from src.utils.file_utils import clear_markdown_data_cache
from src.web.chat.runtime import generate_dialogue_responses_for_run


class _StreamResponse:
    def __init__(
        self, lines, *, status_code=200, reason="OK", encoding="utf-8"
    ):
        self._lines = [
            line if isinstance(line, bytes) else str(line).encode("utf-8")
            for line in lines
        ]
        self.status_code = status_code
        self.reason = reason
        self.encoding = encoding
        self.text = "".join(
            line.decode("utf-8", errors="replace") for line in self._lines
        )

    def iter_lines(self, chunk_size=512, decode_unicode=False):
        self.chunk_sizes = getattr(self, "chunk_sizes", []) + [chunk_size]
        return iter(self._lines)

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False


class _FailingStreamResponse(_StreamResponse):
    def iter_lines(self, chunk_size=512, decode_unicode=False):
        self.chunk_sizes = getattr(self, "chunk_sizes", []) + [chunk_size]
        yield from self._lines
        raise ConnectionResetError("connection closed after a partial response")


class _JsonResponse:
    def __init__(self, payload):
        self.text = json.dumps(payload)
        self.status_code = 200
        self.reason = "OK"

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False


def _sse(payload):
    if payload == "[DONE]":
        return b"data: [DONE]\n"
    return f"data: {json.dumps(payload, ensure_ascii=False)}\n".encode("utf-8")


def _ndjson(payload):
    return f"{json.dumps(payload, ensure_ascii=False)}\n".encode("utf-8")


class LLMClientStreamingTests(unittest.TestCase):
    def setUp(self):
        clear_config_cache()
        clear_markdown_data_cache()

    def tearDown(self):
        clear_config_cache()
        clear_markdown_data_cache()

    def _make_client(self, provider: str, *, host_bridge: bool = False) -> LLMClient:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        config_path = Path(tmp.name) / "config.yaml"
        lines = [
            "llm:",
            f"  provider: {provider}",
            "  model: stream-test",
            "  api_key: test-key",
            "  base_url: https://example.test/v1",
            "  retry_attempts: 3",
            "  retry_backoff_seconds: 0",
        ]
        if host_bridge:
            lines.append("  host_bridge_url: https://bridge.example.test/chat/completions")
        config_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        client = LLMClient(Config(str(config_path)))
        self.addCleanup(client.flush_cost_stats)
        return client

    def test_openai_stream_emits_content_and_aggregates_usage(self):
        client = self._make_client("openai")
        response = _StreamResponse(
            [
                _sse(
                    {
                        "model": "gpt-stream",
                        "choices": [{"delta": {"content": "{\"responses\":["}}],
                    }
                ),
                _sse(
                    {
                        "model": "gpt-stream",
                        "choices": [
                            {
                                "delta": {"content": "{\"speaker\":\"甲\"}"},
                                "finish_reason": "stop",
                            }
                        ],
                    }
                ),
                _sse(
                    {
                        "choices": [],
                        "usage": {
                            "prompt_tokens": 12,
                            "completion_tokens": 7,
                            "prompt_tokens_details": {"cached_tokens": 5},
                        },
                    }
                ),
                _sse("[DONE]"),
            ]
        )
        deltas = []
        with patch(
            "src.core.llm_client.requests.post", return_value=response
        ) as post:
            result = client.chat_completion_stream(
                [{"role": "user", "content": "继续"}],
                on_delta=deltas.append,
            )

        self.assertEqual(
            result["content"], '{"responses":[{"speaker":"甲"}'
        )
        self.assertEqual("".join(deltas), result["content"])
        self.assertEqual(result["model"], "gpt-stream")
        self.assertEqual(result["finish_reason"], "stop")
        self.assertEqual(result["prompt_tokens"], 12)
        self.assertEqual(result["completion_tokens"], 7)
        self.assertEqual(result["cache_usage"]["hit_tokens"], 5)
        self.assertEqual(client.request_count, 1)
        request_payload = post.call_args.kwargs["json"]
        self.assertTrue(request_payload["stream"])
        self.assertEqual(request_payload["stream_options"], {"include_usage": True})
        self.assertNotIn("reasoning_effort", request_payload)
        self.assertEqual(response.chunk_sizes, [1])

    def test_openai_stream_decodes_utf8_and_ignores_empty_sse_fields(self):
        client = self._make_client("openai-compatible")
        response = _StreamResponse(
            [
                b"data:\n",
                b"x-provider-heartbeat: active\n",
                _sse(
                    {
                        "choices": [
                            {
                                "delta": {"content": "你好世界"},
                                "finish_reason": "stop",
                            }
                        ]
                    }
                ),
                _sse("[DONE]"),
            ],
            encoding="ISO-8859-1",
        )

        with patch("src.core.llm_client.requests.post", return_value=response):
            result = client.chat_completion_stream(
                [{"role": "user", "content": "请回复中文"}]
            )

        self.assertEqual(result["content"], "你好世界")
        self.assertEqual(result["finish_reason"], "stop")

    def test_openai_stream_reports_reasoning_activity_without_exposing_it_as_content(self):
        client = self._make_client("openai")
        client.llm_config.update({"model": "gpt-5-mini", "reasoning_effort": "high"})
        response = _StreamResponse(
            [
                _sse(
                    {
                        "choices": [
                            {
                                "delta": {"reasoning_content": "hidden thought"},
                                "finish_reason": None,
                            }
                        ]
                    }
                ),
                _sse(
                    {
                        "choices": [
                            {
                                "delta": {"content": "visible"},
                                "finish_reason": "stop",
                            }
                        ]
                    }
                ),
                _sse("[DONE]"),
            ]
        )
        visible: list[str] = []
        reasoning: list[str] = []

        def on_visible(delta: str) -> None:
            visible.append(delta)

        on_visible.on_reasoning = reasoning.append  # type: ignore[attr-defined]

        with patch("src.core.llm_client.requests.post", return_value=response) as post:
            result = client.chat_completion_stream(
                [{"role": "user", "content": "继续"}],
                on_delta=on_visible,
            )

        self.assertEqual(visible, ["visible"])
        self.assertEqual(reasoning, ["hidden thought"])
        self.assertEqual(result["content"], "visible")
        self.assertEqual(post.call_args.kwargs["json"]["reasoning_effort"], "high")

    def test_openai_non_reasoning_model_omits_stale_reasoning_effort(self):
        client = self._make_client("openai")
        client.llm_config.update(
            {"model": "gpt-4.1-mini", "reasoning_effort": "high"}
        )
        response = {"choices": [{"message": {"content": "done"}}]}

        with patch.object(client, "_post_json", return_value=response) as post:
            client.chat_completion([{"role": "user", "content": "continue"}])

        self.assertNotIn("reasoning_effort", post.call_args.kwargs["payload"])

    def test_openai_compatible_gateway_forwards_supported_gpt5_effort(self):
        client = self._make_client("openai-compatible")
        client.llm_config.update({"model": "gpt-5-mini", "reasoning_effort": "high"})
        response = {"choices": [{"message": {"content": "done"}}]}

        with patch.object(client, "_post_json", return_value=response) as post:
            client.chat_completion([{"role": "user", "content": "continue"}])

        self.assertEqual(post.call_args.kwargs["payload"]["reasoning_effort"], "high")

    def test_openai_o_series_omits_unsupported_off_and_xhigh_efforts(self):
        for effort in ("off", "xhigh"):
            with self.subTest(effort=effort):
                client = self._make_client("openai")
                client.llm_config.update({"model": "o3-mini", "reasoning_effort": effort})
                response = {"choices": [{"message": {"content": "done"}}]}
                with patch.object(client, "_post_json", return_value=response) as post:
                    client.chat_completion([{"role": "user", "content": "continue"}])
                self.assertNotIn("reasoning_effort", post.call_args.kwargs["payload"])

    def test_dashscope_qwen_off_disables_thinking(self):
        client = self._make_client("openai-compatible")
        client.llm_config.update(
            {
                "base_url": "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "model": "qwen-plus",
                "reasoning_effort": "off",
            }
        )
        response = {"choices": [{"message": {"content": "done"}}]}

        with patch.object(client, "_post_json", return_value=response) as post:
            client.chat_completion([{"role": "user", "content": "continue"}])

        self.assertIs(post.call_args.kwargs["payload"]["enable_thinking"], False)

    def test_openai_compatible_stream_omits_reasoning_effort(self):
        client = self._make_client("openai-compatible")
        client.llm_config["reasoning_effort"] = "high"
        response = _StreamResponse([_sse("[DONE]")])

        with patch("src.core.llm_client.requests.post", return_value=response) as post:
            client.chat_completion_stream([{"role": "user", "content": "继续"}])

        self.assertNotIn("reasoning_effort", post.call_args.kwargs["json"])

    def test_openai_compatible_non_stream_omits_reasoning_effort(self):
        client = self._make_client("openai-compatible")
        client.llm_config["reasoning_effort"] = "high"
        response = {"choices": [{"message": {"content": "完成"}}]}

        with patch.object(client, "_post_json", return_value=response) as post:
            client.chat_completion([{"role": "user", "content": "继续"}])

        self.assertNotIn("reasoning_effort", post.call_args.kwargs["payload"])

    def test_stepfun_custom_model_applies_supported_reasoning_controls(self):
        client = self._make_client("openai-compatible")
        response = {"choices": [{"message": {"content": "完成"}}]}

        for effort, expected in (("auto", None), ("off", None), ("high", "high")):
            with self.subTest(effort=effort):
                client.llm_config.update(
                    {
                        "model": "step-3.5-flash",
                        "reasoning_effort": effort,
                    }
                )
                with patch.object(client, "_post_json", return_value=response) as post:
                    client.chat_completion([{"role": "user", "content": "继续"}])

                payload = post.call_args.kwargs["payload"]
                if expected is None:
                    self.assertNotIn("reasoning_effort", payload)
                else:
                    self.assertEqual(payload["reasoning_effort"], expected)

    def test_stepfun_2603_omits_unsupported_medium_reasoning_effort(self):
        client = self._make_client("openai-compatible")
        client.llm_config.update(
            {
                "model": "step-3.5-flash-2603",
                "reasoning_effort": "medium",
            }
        )
        response = {"choices": [{"message": {"content": "完成"}}]}

        with patch.object(client, "_post_json", return_value=response) as post:
            client.chat_completion([{"role": "user", "content": "继续"}])

        self.assertNotIn("reasoning_effort", post.call_args.kwargs["payload"])

    def test_direct_deepseek_flash_uses_low_reasoning_effort(self):
        client = self._make_client("openai-compatible")
        client.llm_config.update(
            {
                "base_url": "https://api.deepseek.com",
                "model": "deepseek-v4-flash",
                "reasoning_effort": "low",
            }
        )
        response = _StreamResponse([_sse("[DONE]")])

        with patch("src.core.llm_client.requests.post", return_value=response) as post:
            client.chat_completion_stream([{"role": "user", "content": "继续"}])

        request_payload = post.call_args.kwargs["json"]
        self.assertEqual(request_payload["reasoning_effort"], "low")
        self.assertNotIn("thinking", request_payload)

    def test_direct_deepseek_pro_low_effort_stays_low(self):
        client = self._make_client("openai-compatible")
        client.llm_config.update(
            {
                "base_url": "https://api.deepseek.com",
                "model": "deepseek-v4-pro",
                "reasoning_effort": "low",
            }
        )
        response = {"choices": [{"message": {"content": "完成"}}]}

        with patch.object(client, "_post_json", return_value=response) as post:
            client.chat_completion([{"role": "user", "content": "继续"}])

        request_payload = post.call_args.kwargs["payload"]
        self.assertEqual(request_payload["reasoning_effort"], "low")
        self.assertNotIn("thinking", request_payload)

    def test_direct_deepseek_off_disables_thinking(self):
        client = self._make_client("openai-compatible")
        client.llm_config.update(
            {
                "base_url": "https://api.deepseek.com",
                "model": "deepseek-v4-pro",
                "reasoning_effort": "off",
            }
        )
        response = {"choices": [{"message": {"content": "完成"}}]}

        with patch.object(client, "_post_json", return_value=response) as post:
            client.chat_completion([{"role": "user", "content": "继续"}])

        request_payload = post.call_args.kwargs["payload"]
        self.assertEqual(request_payload["thinking"], {"type": "disabled"})
        self.assertNotIn("reasoning_effort", request_payload)

    def test_openai_off_uses_none_reasoning_effort(self):
        client = self._make_client("openai")
        client.llm_config.update(
            {"model": "gpt-5", "reasoning_effort": "off"}
        )
        response = {"choices": [{"message": {"content": "完成"}}]}

        with patch.object(client, "_post_json", return_value=response) as post:
            client.chat_completion([{"role": "user", "content": "继续"}])

        self.assertEqual(
            post.call_args.kwargs["payload"]["reasoning_effort"], "none"
        )

    def test_stream_keeps_idle_timeout_separate_from_the_total_deadline(self):
        client = self._make_client("openai-compatible")
        client.llm_config["timeout_seconds"] = 1
        client.llm_config["retry_attempts"] = 1
        response = _StreamResponse(
            [
                _sse(
                    {
                        "choices": [
                            {
                                "delta": {"reasoning_content": "仍在思考"},
                                "finish_reason": None,
                            }
                        ]
                    }
                ),
                _sse("[DONE]"),
            ]
        )

        with patch("src.core.llm_client.requests.post", return_value=response) as post:
            result = client.chat_completion_stream(
                [{"role": "user", "content": "continue"}]
            )

        self.assertEqual(result["content"], "")
        self.assertEqual(post.call_args.kwargs["timeout"], 1)

    def test_stream_enforces_the_separate_total_timeout(self):
        client = self._make_client("openai-compatible")
        client.llm_config.update(
            {"timeout_seconds": 90, "stream_total_timeout_seconds": 1, "retry_attempts": 1}
        )
        response = _StreamResponse([_sse({"choices": []})])

        with (
            patch("src.core.llm_client.requests.post", return_value=response),
            patch("src.core.llm_client.time.monotonic", side_effect=[0.0, 2.0]),
        ):
            with self.assertRaisesRegex(LLMRequestError, "超过 1 秒总时限"):
                client.chat_completion_stream([{"role": "user", "content": "continue"}])

    def test_anthropic_stream_emits_text_and_preserves_cache_usage(self):
        client = self._make_client("anthropic")
        response = _StreamResponse(
            [
                b"event: message_start\n",
                _sse(
                    {
                        "type": "message_start",
                        "message": {
                            "model": "claude-stream",
                            "usage": {
                                "input_tokens": 20,
                                "cache_read_input_tokens": 70,
                                "cache_creation_input_tokens": 10,
                            },
                        },
                    }
                ),
                _sse(
                    {
                        "type": "content_block_delta",
                        "delta": {"type": "text_delta", "text": "第一段"},
                    }
                ),
                _sse(
                    {
                        "type": "content_block_delta",
                        "delta": {"type": "text_delta", "text": " 第二段"},
                    }
                ),
                _sse(
                    {
                        "type": "message_delta",
                        "delta": {"stop_reason": "end_turn"},
                        "usage": {"output_tokens": 6},
                    }
                ),
                _sse({"type": "message_stop"}),
            ]
        )
        deltas = []
        with patch(
            "src.core.llm_client.requests.post", return_value=response
        ) as post:
            result = client.chat_completion_stream(
                [
                    {"role": "system", "content": "固定资料", "cache_static": True},
                    {"role": "user", "content": "继续"},
                ],
                on_delta=deltas.append,
            )

        self.assertEqual(deltas, ["第一段", " 第二段"])
        self.assertEqual(result["content"], "第一段 第二段")
        self.assertEqual(result["model"], "claude-stream")
        self.assertEqual(result["finish_reason"], "end_turn")
        self.assertEqual(result["prompt_tokens"], 100)
        self.assertEqual(result["completion_tokens"], 6)
        self.assertEqual(result["cache_usage"]["hit_tokens"], 70)
        request_payload = post.call_args.kwargs["json"]
        self.assertTrue(request_payload["stream"])
        self.assertEqual(
            request_payload["system"][0]["cache_control"], {"type": "ephemeral"}
        )

    def test_ollama_stream_reads_ndjson_and_final_token_counts(self):
        client = self._make_client("ollama")
        response = _StreamResponse(
            [
                _ndjson(
                    {
                        "model": "qwen-local",
                        "message": {"content": "你"},
                        "done": False,
                    }
                ),
                _ndjson(
                    {
                        "model": "qwen-local",
                        "message": {"content": "好"},
                        "done": False,
                    }
                ),
                _ndjson(
                    {
                        "model": "qwen-local",
                        "message": {"content": ""},
                        "done": True,
                        "done_reason": "stop",
                        "prompt_eval_count": 9,
                        "eval_count": 2,
                    }
                ),
            ]
        )
        deltas = []
        with patch(
            "src.core.llm_client.requests.post", return_value=response
        ) as post:
            result = client.chat_completion_stream(
                [{"role": "user", "content": "打招呼"}],
                on_delta=deltas.append,
            )

        self.assertEqual(deltas, ["你", "好"])
        self.assertEqual(result["content"], "你好")
        self.assertEqual(result["model"], "qwen-local")
        self.assertEqual(result["prompt_tokens"], 9)
        self.assertEqual(result["completion_tokens"], 2)
        request_payload = post.call_args.kwargs["json"]
        self.assertTrue(request_payload["stream"])

    def test_host_bridge_falls_back_to_one_complete_delta(self):
        client = self._make_client("host-bridge", host_bridge=True)
        deltas = []
        with patch(
            "src.core.llm_client.requests.post",
            return_value=_JsonResponse(
                {
                    "content": "桥接完整回复",
                    "model": "bridge-model",
                    "prompt_tokens": 8,
                    "completion_tokens": 4,
                }
            ),
        ):
            result = client.chat_completion_stream(
                [{"role": "user", "content": "继续"}],
                on_delta=deltas.append,
            )

        self.assertEqual(deltas, ["桥接完整回复"])
        self.assertEqual(result["content"], "桥接完整回复")
        self.assertEqual(result["provider"], "host-bridge")

    def test_stream_does_not_retry_after_a_provider_event(self):
        client = self._make_client("openai-compatible")
        response = _FailingStreamResponse(
            [
                _sse(
                    {
                        "choices": [{"delta": {"content": "partial"}}],
                    }
                )
            ]
        )
        deltas = []
        with patch(
            "src.core.llm_client.requests.post", return_value=response
        ) as post:
            with self.assertRaises(LLMRequestError):
                client.chat_completion_stream(
                    [{"role": "user", "content": "继续"}],
                    on_delta=deltas.append,
                )

        self.assertEqual(deltas, ["partial"])
        self.assertEqual(post.call_count, 1)

    def test_clean_eof_without_provider_completion_event_is_rejected(self):
        cases = (
            (
                "openai-compatible",
                [_sse({"choices": [{"delta": {"content": "partial"}}]})],
            ),
            (
                "anthropic",
                [
                    _sse(
                        {
                            "type": "content_block_delta",
                            "delta": {"type": "text_delta", "text": "partial"},
                        }
                    )
                ],
            ),
            (
                "ollama",
                [
                    _ndjson(
                        {
                            "model": "local-stream",
                            "message": {"content": "partial"},
                            "done": False,
                        }
                    )
                ],
            ),
        )

        for provider, lines in cases:
            with self.subTest(provider=provider):
                client = self._make_client(provider)
                deltas = []
                with patch(
                    "src.core.llm_client.requests.post",
                    return_value=_StreamResponse(lines),
                ) as post:
                    with self.assertRaisesRegex(
                        LLMRequestError,
                        "completion event",
                    ):
                        client.chat_completion_stream(
                            [{"role": "user", "content": "继续"}],
                            on_delta=deltas.append,
                        )

                self.assertEqual(deltas, ["partial"])
                self.assertEqual(post.call_count, 1)


class _RuntimeConfig:
    def get(self, key, default=None):
        values = {
            "llm.temperature": 0.3,
            "llm.max_tokens": 300,
        }
        return values.get(key, default)


class _RuntimeStreamingLLM:
    def __init__(self):
        self.stream_calls = 0
        self.sync_calls = 0

    def chat_completion_stream(
        self, messages, *, temperature=None, max_tokens=None, on_delta=None
    ):
        del messages, temperature, max_tokens
        self.stream_calls += 1
        chunks = ['[{"speaker":"甲",', '"message":"你好"}]']
        for chunk in chunks:
            on_delta(chunk)
        return {
            "content": "".join(chunks),
            "model": "stream-test",
            "provider": "test",
            "prompt_tokens": 3,
            "completion_tokens": 4,
        }

    def chat_completion(self, messages, *, temperature=None, max_tokens=None):
        del messages, temperature, max_tokens
        self.sync_calls += 1
        raise AssertionError("runtime should prefer chat_completion_stream")


class RuntimeStreamingTests(unittest.TestCase):
    def test_runtime_forwards_raw_deltas_and_attempt_number(self):
        llm = _RuntimeStreamingLLM()
        deltas = []
        attempts = []

        def generate(**kwargs):
            result = kwargs["chat_completion"](
                [{"role": "user", "content": "继续"}],
                kwargs["temperature"],
                kwargs["max_tokens"],
            )
            kwargs["completion_observer"](result)
            return kwargs["parse_responses"](
                result["content"], kwargs["allowed_speakers"]
            )

        result = generate_dialogue_responses_for_run(
            run_dir=Path("run-test"),
            payload={"responder_hints": [{"name": "甲"}]},
            build_runtime_config_for_run=lambda **_kwargs: _RuntimeConfig(),
            build_runtime_parts=lambda _config: type("Parts", (), {"llm": llm})(),
            generate_dialogue_responses=generate,
            build_dialogue_llm_messages=lambda _payload, _retry: [],
            parse_dialogue_responses=lambda content, _allowed: json.loads(content),
            on_delta=deltas.append,
            on_attempt=attempts.append,
        )

        self.assertEqual(deltas, ['[{"speaker":"甲",', '"message":"你好"}]'])
        self.assertEqual(attempts, [0])
        self.assertEqual(llm.stream_calls, 1)
        self.assertEqual(llm.sync_calls, 0)
        self.assertEqual(result["responses"], [{"speaker": "甲", "message": "你好"}])


if __name__ == "__main__":
    unittest.main()
