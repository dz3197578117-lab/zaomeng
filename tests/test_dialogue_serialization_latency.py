from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


class DialogueSerializationLatencyTests(unittest.TestCase):
    def test_prepare_turn_can_skip_internal_session_serialization(self):
        try:
            from src.web.chat.service import DialogueService
        except ModuleNotFoundError as exc:
            self.skipTest(f"optional runtime dependency unavailable: {exc}")

        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            persona_dir = root / "personas" / "A"
            persona_dir.mkdir(parents=True, exist_ok=True)
            profile_path = persona_dir / "PROFILE.md"
            profile_path.write_text(
                "# PROFILE\n- name: A\n- core_identity: test character\n",
                encoding="utf-8",
            )
            manifest = {
                "run_id": "run-fast-prepare",
                "novel_title": "Test",
                "artifact_index": {
                    "characters": [
                        {
                            "name": "A",
                            "profile_file": str(profile_path),
                            "persona_dir": str(persona_dir),
                        }
                    ]
                },
            }
            dialogue = DialogueService(root / "runs")
            created = dialogue.create_session(
                manifest,
                mode="observe",
                participants=["A"],
            )

            with patch.object(
                dialogue,
                "_serialize_session",
                wraps=dialogue._serialize_session,
            ) as serialize_session:
                prepared = dialogue.prepare_turn(
                    manifest,
                    session_id=created["session_id"],
                    message="Continue.",
                    _serialize_result=False,
                )

            serialize_session.assert_not_called()
            self.assertTrue(prepared["pending_turn_summary"]["turn_id"])
            self.assertEqual(
                set(prepared),
                {"pending_turn_summary"},
            )

    def test_session_serialization_reuses_one_completed_turn_scan(self):
        try:
            from src.web.chat.service import DialogueService
        except ModuleNotFoundError as exc:
            self.skipTest(f"optional runtime dependency unavailable: {exc}")

        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            persona_dir = root / "personas" / "A"
            persona_dir.mkdir(parents=True, exist_ok=True)
            profile_path = persona_dir / "PROFILE.md"
            profile_path.write_text(
                "# PROFILE\n- name: A\n- core_identity: test character\n",
                encoding="utf-8",
            )
            manifest = {
                "run_id": "run-serialize-latency",
                "novel_id": "novel-1",
                "artifact_index": {
                    "characters": [
                        {
                            "name": "A",
                            "profile_file": str(profile_path),
                            "persona_dir": str(persona_dir),
                        }
                    ]
                },
            }
            dialogue = DialogueService(root / "runs")
            created = dialogue.create_session(
                manifest,
                mode="observe",
                participants=["A"],
            )
            session_id = created["session_id"]
            dialogue.prepare_turn(
                manifest,
                session_id=session_id,
                message="Continue.",
            )
            dialogue.ingest_turn_responses(
                "run-serialize-latency",
                session_id=session_id,
                responses=[{"speaker": "A", "message": "Reply."}],
            )

            baseline = dialogue.get_session("run-serialize-latency", session_id)
            original = dialogue._completed_turn_records
            scan_count = 0

            def counted_records(run_id: str, current_session_id: str):
                nonlocal scan_count
                scan_count += 1
                return original(run_id, current_session_id)

            dialogue._completed_turn_records = counted_records
            serialized = dialogue.get_session("run-serialize-latency", session_id)

            self.assertEqual(scan_count, 1)
            for field in (
                "event_timeline",
                "relation_timeline",
                "speaker_activity",
                "character_arcs",
                "chapter_outline",
            ):
                self.assertEqual(serialized[field], baseline[field])


if __name__ == "__main__":
    unittest.main()
