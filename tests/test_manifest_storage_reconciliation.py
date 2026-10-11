from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from src.web.manifest.store import reconcile_loaded_manifest


class ManifestStorageReconciliationTests(unittest.TestCase):
    def test_legacy_import_is_rebased_to_its_actual_run_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_dir = Path(tmp) / "runs" / "run-new"
            manifest_path = run_dir / "run_manifest.json"
            source_root = Path("/data/data/com.termux/files/home/runs/run-old")
            payload = {
                "run_id": "run-old",
                "novel_id": "demo",
                "status": "ready",
                "created_at": "2026-01-01T00:00:00Z",
                "webui": {
                    "run_dir": str(source_root),
                    "input_dir": str(source_root / "input"),
                    "payload_dir": str(source_root / "payloads"),
                    "artifact_dir": str(source_root / "artifacts"),
                },
                "novel_path": str(source_root / "input" / "demo.txt"),
                "novel_sources": [
                    {"source_path": str(source_root / "input" / "demo.txt")}
                ],
                "artifact_index": {
                    "characters": [
                        {"name": "甲", "path": str(source_root / "artifacts" / "甲.md")}
                    ]
                },
            }

            reconciled, changed = reconcile_loaded_manifest(
                manifest_path,
                payload,
                is_thread_alive=lambda _: False,
                utc_now=lambda: "2026-01-02T00:00:00Z",
                finalize_manifest_timing=lambda *_: None,
            )

            target_root = run_dir.resolve()
            self.assertTrue(changed)
            self.assertEqual(reconciled["run_id"], "run-new")
            self.assertEqual(reconciled["created_at"], "2026-01-01T00:00:00Z")
            self.assertEqual(reconciled["webui"]["run_dir"], str(target_root))
            self.assertEqual(
                reconciled["novel_path"], str(target_root / "input" / "demo.txt")
            )
            self.assertEqual(
                reconciled["novel_sources"][0]["source_path"],
                str(target_root / "input" / "demo.txt"),
            )
            self.assertEqual(
                reconciled["artifact_index"]["characters"][0]["path"],
                str(target_root / "artifacts" / "甲.md"),
            )

            second, changed_again = reconcile_loaded_manifest(
                manifest_path,
                reconciled,
                is_thread_alive=lambda _: False,
                utc_now=lambda: "2026-01-02T00:00:00Z",
                finalize_manifest_timing=lambda *_: None,
            )
            self.assertFalse(changed_again)
            self.assertEqual(second, reconciled)


if __name__ == "__main__":
    unittest.main()
