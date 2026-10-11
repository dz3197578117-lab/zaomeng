from __future__ import annotations

import tempfile
import unittest
from unittest.mock import Mock, patch

from fastapi.testclient import TestClient
from src.web.app import create_app
from src.web.workflow import WebRunService


class ModelProfileServiceTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.service = WebRunService(self.tmp.name)

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def test_saves_switches_and_deletes_local_model_profiles(self) -> None:
        first = self.service.save_model_settings(
            provider="openai-compatible",
            model="model-a",
            api_key="key-a",
            profile_name="First",
            reasoning_effort="high",
        )
        self.assertEqual(first["active_profile_id"], "default")
        self.assertEqual(len(first["profiles"]), 1)
        self.assertEqual(first["reasoning_effort"], "high")
        self.assertEqual(first["profiles"][0]["reasoning_effort"], "high")

        second = self.service.save_model_settings(
            provider="openai-compatible",
            model="model-b",
            api_key="key-b",
            profile_name="Second",
            create_profile=True,
        )
        second_id = second["active_profile_id"]
        self.assertNotEqual(second_id, "default")
        self.assertEqual(len(second["profiles"]), 2)
        self.assertEqual(self.service._load_model_settings_payload()["model"], "model-b")

        switched = self.service.activate_model_profile("default")
        self.assertEqual(switched["active_profile_id"], "default")
        self.assertEqual(self.service._load_model_settings_payload()["api_key"], "key-a")

        deleted = self.service.delete_model_profile(second_id)
        self.assertEqual(len(deleted["profiles"]), 1)
        self.assertEqual(deleted["active_profile_id"], "default")

    def test_cannot_delete_the_last_profile(self) -> None:
        self.service.save_model_settings(
            provider="ollama",
            model="llama3",
            profile_name="Local",
        )

        with self.assertRaises(ValueError):
            self.service.delete_model_profile("default")

    def test_rejects_unknown_reasoning_effort(self) -> None:
        with self.assertRaisesRegex(ValueError, "Reasoning effort"):
            self.service.save_model_settings(
                provider="ollama",
                model="llama3",
                reasoning_effort="maximum",
            )

    def test_accepts_disabled_reasoning_effort(self) -> None:
        saved = self.service.save_model_settings(
            provider="openai-compatible",
            model="deepseek-v4-pro",
            base_url="https://api.deepseek.com",
            api_key="key-a",
            reasoning_effort="off",
        )

        self.assertEqual(saved["reasoning_effort"], "off")

    def test_routes_create_and_activate_profiles(self) -> None:
        client = TestClient(create_app(self.service))
        initial = client.put(
            "/api/web/settings/model",
            json={"provider": "ollama", "model": "llama3", "profile_name": "Local"},
        )
        self.assertEqual(initial.status_code, 200)
        created = client.put(
            "/api/web/settings/model",
            json={
                "provider": "openai-compatible",
                "model": "model-b",
                "api_key": "key-b",
                "profile_name": "Cloud",
                "create_profile": True,
            },
        )
        self.assertEqual(created.status_code, 200)
        self.assertEqual(len(created.json()["profiles"]), 2)
        activated = client.post("/api/web/settings/model/profiles/default/activate")
        self.assertEqual(activated.status_code, 200)
        self.assertEqual(activated.json()["active_profile_id"], "default")

    @patch("src.web.service_facades.runs.LLMClient")
    def test_tests_draft_connection_without_saving_it(self, llm_client: Mock) -> None:
        llm_client.return_value.chat_completion.return_value = {
            "provider": "openai-compatible",
            "model": "draft-model",
            "content": "OK",
        }

        result = self.service.test_model_connection(
            provider="openai-compatible",
            model="draft-model",
            api_key="draft-key",
        )

        self.assertTrue(result["ok"])
        self.assertEqual(result["model"], "draft-model")
        self.assertEqual(self.service.get_model_settings()["profiles"], [])

    def test_connection_test_route(self) -> None:
        self.service.test_model_connection = Mock(
            return_value={
                "ok": True,
                "provider": "ollama",
                "model": "llama3",
                "latency_ms": 12,
                "message": "连接成功。",
            }
        )
        client = TestClient(create_app(self.service))

        response = client.post(
            "/api/web/settings/model/test",
            json={"provider": "ollama", "model": "llama3"},
        )

        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.json()["ok"])


if __name__ == "__main__":
    unittest.main()
