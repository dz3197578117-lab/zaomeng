#!/usr/bin/env python3

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import requests

from src.core.config import Config, clear_config_cache
from src.core.exceptions import LLMRequestError
from src.core.llm_client import LLMClient
from src.utils.file_utils import clear_markdown_data_cache


class _Response:
    def __init__(self, payload, *, status_code=200, reason="OK"):
        self.text = json.dumps(payload)
        self.status_code = status_code
        self.reason = reason

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False


class LLMRetryTests(unittest.TestCase):
    def setUp(self):
        clear_config_cache()
        clear_markdown_data_cache()

    def tearDown(self):
        clear_config_cache()
        clear_markdown_data_cache()

    def _make_client(self, provider: str = "openai") -> LLMClient:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        config_path = Path(tmp.name) / "config.yaml"
        config_path.write_text(
            "\n".join(
                [
                    "llm:",
                    f"  provider: {provider}",
                    "  model: gpt-test",
                    "  api_key: test-key",
                    "  retry_attempts: 3",
                    "  retry_backoff_seconds: 0.01",
                    "  retry_backoff_multiplier: 2",
                ]
            )
            + "\n",
            encoding="utf-8",
        )
        return LLMClient(Config(str(config_path)))

    def _make_local_client(self) -> LLMClient:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        config_path = Path(tmp.name) / "config.yaml"
        config_path.write_text(
            "\n".join(
                [
                    "llm:",
                    "  provider: local-rule-engine",
                    "  model: local-rule-engine",
                ]
            )
            + "\n",
            encoding="utf-8",
        )
        return LLMClient(Config(str(config_path)))

    def test_default_config_prefers_auto_provider(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        config_path = Path(tmp.name) / "config.yaml"
        config_path.write_text("", encoding="utf-8")

        client = LLMClient(Config(str(config_path)))

        self.assertEqual(client.llm_config.get("provider"), "auto")
        self.assertEqual(client.provider_name(), "local-rule-engine")

    def test_post_json_retries_url_errors_then_succeeds(self):
        client = self._make_client()
        with patch(
            "src.core.llm_client.requests.post",
            side_effect=[requests.ConnectionError("temporary"), _Response({"ok": True})],
        ) as post, patch("src.core.llm_client.time.sleep") as sleep:
            result = client._post_json(
                url="https://example.test", payload={"ping": "pong"}
            )

        self.assertEqual(result, {"ok": True})
        self.assertEqual(post.call_count, 2)
        sleep.assert_called_once_with(0.01)

    def test_post_json_wraps_connection_reset_as_llm_request_error(self):
        client = self._make_client()
        with patch(
            "src.core.llm_client.requests.post",
            side_effect=ConnectionResetError(
                "[WinError 10054] remote host closed connection"
            ),
        ) as post, patch("src.core.llm_client.time.sleep") as sleep:
            with self.assertRaises(LLMRequestError) as ctx:
                client._post_json(url="https://example.test", payload={"ping": "pong"})

        self.assertIn("LLM 连接失败", str(ctx.exception))
        self.assertEqual(post.call_count, 3)
        self.assertEqual(sleep.call_count, 2)

    def test_post_json_does_not_retry_non_retryable_http_errors(self):
        client = self._make_client()
        with patch(
            "src.core.llm_client.requests.post",
            return_value=_Response(
                {"error": "bad request"}, status_code=400, reason="Bad Request"
            ),
        ) as post, patch("src.core.llm_client.time.sleep") as sleep:
            with self.assertRaises(LLMRequestError):
                client._post_json(url="https://example.test", payload={"ping": "pong"})

        self.assertEqual(post.call_count, 1)
        sleep.assert_not_called()

    def test_local_provider_auto_promotes_to_openai_when_env_key_exists(self):
        client = self._make_local_client()
        with patch.dict("os.environ", {"OPENAI_API_KEY": "env-key"}, clear=False):
            self.assertEqual(client.provider_name(), "openai")
            self.assertTrue(client.is_generation_enabled())
            self.assertEqual(
                client._resolve_model_name(client.provider_name()), "gpt-4.1-mini"
            )

    def test_local_provider_uses_env_model_for_ollama(self):
        client = self._make_local_client()
        with patch.dict("os.environ", {"OLLAMA_MODEL": "qwen2.5:14b"}, clear=False):
            self.assertEqual(client.provider_name(), "ollama")
            self.assertEqual(client._resolve_model_name("ollama"), "qwen2.5:14b")

    def test_host_bridge_has_highest_auto_detection_priority(self):
        client = self._make_local_client()
        with patch.dict(
            "os.environ",
            {
                "ZAOMENG_HOST_BRIDGE_URL": "http://127.0.0.1:8765",
                "OPENAI_API_KEY": "env-key",
            },
            clear=False,
        ):
            self.assertEqual(client.provider_name(), "host-bridge")
            self.assertEqual(client._resolve_model_name("host-bridge"), "host-default")
            self.assertEqual(
                client._resolve_host_bridge_url(),
                "http://127.0.0.1:8765/chat/completions",
            )

    def test_host_bridge_parses_simple_bridge_payload(self):
        client = self._make_local_client()
        with patch.dict(
            "os.environ",
            {"ZAOMENG_HOST_BRIDGE_URL": "http://127.0.0.1:8765"},
            clear=False,
        ), patch(
            "src.core.llm_client.requests.post",
            return_value=_Response(
                {
                    "content": "桥接回复",
                    "model": "host-llm",
                    "prompt_tokens": 11,
                    "completion_tokens": 7,
                    "usage": {
                        "prompt_tokens": 11,
                        "prompt_cache_hit_tokens": 8,
                        "prompt_cache_miss_tokens": 3,
                    },
                }
            ),
        ) as post:
            result = client.chat_completion(
                [
                    {
                        "role": "system",
                        "content": "稳定角色资料",
                        "cache_static": True,
                    },
                    {"role": "user", "content": "你好"},
                ]
            )

        self.assertEqual(result["provider"], "host-bridge")
        self.assertEqual(result["content"], "桥接回复")
        self.assertEqual(result["model"], "host-llm")
        self.assertEqual(result["cache_usage"]["hit_tokens"], 8)
        self.assertAlmostEqual(result["cache_usage"]["hit_rate"], 8 / 11)
        request_payload = post.call_args.kwargs["json"]
        self.assertNotIn("cache_static", request_payload["messages"][0])

    def test_openai_like_extracts_text_from_content_parts(self):
        client = self._make_client()
        with patch(
            "src.core.llm_client.requests.post",
            return_value=_Response(
                {
                    "choices": [
                        {
                            "finish_reason": "length",
                            "message": {
                                "content": [
                                    {"type": "text", "text": "???"},
                                    {"type": "text", "text": "???"},
                                ]
                            },
                        }
                    ],
                    "model": "gpt-test",
                    "usage": {"prompt_tokens": 3, "completion_tokens": 5},
                }
            ),
        ):
            result = client.chat_completion([{"role": "user", "content": "??"}])

        self.assertEqual(result["content"], "???\n???")
        self.assertEqual(result["finish_reason"], "length")

    def test_openai_cache_usage_is_normalized_and_internal_hint_is_stripped(self):
        client = self._make_client()
        with patch(
            "src.core.llm_client.requests.post",
            return_value=_Response(
                {
                    "choices": [{"message": {"content": "回复"}}],
                    "model": "gpt-test",
                    "usage": {
                        "prompt_tokens": 100,
                        "completion_tokens": 5,
                        "prompt_tokens_details": {"cached_tokens": 64},
                    },
                }
            ),
        ) as post:
            result = client.chat_completion(
                [
                    {
                        "role": "system",
                        "content": "稳定角色资料",
                        "cache_static": True,
                    },
                    {"role": "user", "content": "继续"},
                ]
            )

        self.assertEqual(
            result["cache_usage"],
            {
                "observable": True,
                "hit_tokens": 64,
                "miss_tokens": 36,
                "creation_tokens": 0,
                "input_tokens": 100,
                "hit_rate": 0.64,
            },
        )
        request_payload = post.call_args.kwargs["json"]
        self.assertNotIn("cache_static", request_payload["messages"][0])

    def test_deepseek_cache_hit_and_miss_are_not_double_counted(self):
        client = self._make_client(provider="openai-compatible")
        with patch(
            "src.core.llm_client.requests.post",
            return_value=_Response(
                {
                    "choices": [{"message": {"content": "回复"}}],
                    "usage": {
                        "prompt_tokens": 100,
                        "completion_tokens": 5,
                        "prompt_cache_hit_tokens": 75,
                        "prompt_cache_miss_tokens": 25,
                    },
                }
            ),
        ):
            result = client.chat_completion([{"role": "user", "content": "继续"}])

        self.assertEqual(result["cache_usage"]["input_tokens"], 100)
        self.assertEqual(result["cache_usage"]["hit_tokens"], 75)
        self.assertEqual(result["cache_usage"]["miss_tokens"], 25)
        self.assertEqual(result["cache_usage"]["hit_rate"], 0.75)

    def test_anthropic_cache_usage_and_static_system_block_are_normalized(self):
        client = self._make_client(provider="anthropic")
        with patch(
            "src.core.llm_client.requests.post",
            return_value=_Response(
                {
                    "content": [{"type": "text", "text": "回复"}],
                    "model": "claude-test",
                    "usage": {
                        "input_tokens": 20,
                        "output_tokens": 5,
                        "cache_read_input_tokens": 70,
                        "cache_creation_input_tokens": 10,
                    },
                }
            ),
        ) as post:
            result = client.chat_completion(
                [
                    {
                        "role": "system",
                        "content": "稳定角色资料",
                        "cache_static": True,
                    },
                    {"role": "system", "content": "动态场景"},
                    {"role": "user", "content": "继续"},
                ]
            )

        self.assertEqual(
            result["cache_usage"],
            {
                "observable": True,
                "hit_tokens": 70,
                "miss_tokens": 20,
                "creation_tokens": 10,
                "input_tokens": 100,
                "hit_rate": 0.7,
            },
        )
        request_payload = post.call_args.kwargs["json"]
        self.assertEqual(
            request_payload["system"][0]["cache_control"], {"type": "ephemeral"}
        )
        self.assertNotIn("cache_control", request_payload["system"][1])
        self.assertNotIn("cache_static", request_payload["messages"][0])

    def test_anthropic_unmarked_system_messages_keep_string_payload(self):
        client = self._make_client(provider="anthropic")
        with patch(
            "src.core.llm_client.requests.post",
            return_value=_Response(
                {
                    "content": [{"type": "text", "text": "回复"}],
                    "usage": {"input_tokens": 10, "output_tokens": 2},
                }
            ),
        ) as post:
            result = client.chat_completion(
                [
                    {"role": "system", "content": "角色资料"},
                    {"role": "system", "content": "场景资料"},
                    {"role": "user", "content": "继续"},
                ]
            )

        request_payload = post.call_args.kwargs["json"]
        self.assertEqual(request_payload["system"], "角色资料\n\n场景资料")
        self.assertFalse(result["cache_usage"]["observable"])
        self.assertIsNone(result["cache_usage"]["hit_rate"])

    def test_ollama_strips_internal_cache_hint_and_marks_usage_unobservable(self):
        client = self._make_local_client()
        with patch(
            "src.core.llm_client.requests.post",
            return_value=_Response(
                {
                    "message": {"content": "回复"},
                    "prompt_eval_count": 12,
                    "eval_count": 3,
                }
            ),
        ) as post:
            result = client._chat_ollama(
                messages=[
                    {
                        "role": "system",
                        "content": "稳定角色资料",
                        "cache_static": True,
                    }
                ],
                model="qwen-test",
                temperature=None,
                max_tokens=None,
            )

        request_payload = post.call_args.kwargs["json"]
        self.assertNotIn("cache_static", request_payload["messages"][0])
        self.assertFalse(result["cache_usage"]["observable"])
        self.assertIsNone(result["cache_usage"]["hit_rate"])


if __name__ == "__main__":
    unittest.main()
