from __future__ import annotations

import base64
import json
import tempfile
import unittest

from src.web.secrets import InMemorySecretStore
from src.web.workflow import WebRunService


class DiagnosticsTests(unittest.TestCase):
    def test_report_is_redacted_and_contains_runtime_summary(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp, secret_store=InMemorySecretStore())
            service.save_model_settings(
                provider="openai-compatible",
                model="demo-model",
                base_url="https://user:pass@example.com/v1?token=visible",
                api_key="sk-super-secret",
            )
            service.create_run(
                novel_name="private-title.txt",
                novel_content_base64=base64.b64encode("private novel body".encode()).decode(),
                characters=["private-character"],
                defer_run=True,
            )

            report = service.build_diagnostics_report()
            serialized = json.dumps(report, ensure_ascii=False)

            self.assertEqual(report["kind"], "zaomeng_diagnostics")
            self.assertIn("example.com/v1", serialized)
            self.assertNotIn("sk-super-secret", serialized)
            self.assertNotIn("user:pass", serialized)
            self.assertNotIn("token=visible", serialized)
            self.assertNotIn("private novel body", serialized)
            self.assertNotIn("private-character", serialized)
            self.assertNotIn("private-title", serialized)

    def test_in_memory_secret_store_never_creates_files(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            store = InMemorySecretStore({"model_api_key": "one"})
            store.write("model_api_key_profile-2", "two")
            store.delete("model_api_key")

            self.assertEqual(store.read("model_api_key"), "")
            self.assertEqual(store.read("model_api_key_profile-2"), "two")
            self.assertEqual(list(__import__("pathlib").Path(tmp).iterdir()), [])


if __name__ == "__main__":
    unittest.main()
