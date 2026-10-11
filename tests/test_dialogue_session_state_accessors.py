from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from src.web.chat.service import DialogueService


class DialogueSessionStateAccessorTests(unittest.TestCase):
    def test_generated_accessors_round_trip_each_state_section(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            service = DialogueService(Path(tmpdir) / "runs")
            session: dict = {}
            cases = [
                (
                    "_session_relation_matrix",
                    "_set_session_relation_matrix",
                    {"A::B": {"trust": 7}},
                ),
                (
                    "_session_relation_delta",
                    "_set_session_relation_delta",
                    {"A::B": {"trust": 1}},
                ),
                (
                    "_session_character_snapshots",
                    "_set_session_character_snapshots",
                    {"A": {"mood": "calm"}},
                ),
                (
                    "_session_event_signals",
                    "_set_session_event_signals",
                    {"recent": [{"kind": "promise"}], "by_type": {}},
                ),
                (
                    "_session_memory_summary_state",
                    "_set_session_memory_summary_state",
                    {"recap": "A replied."},
                ),
            ]

            for getter_name, setter_name, payload in cases:
                with self.subTest(getter=getter_name):
                    getattr(service, setter_name)(session, payload)
                    self.assertEqual(getattr(service, getter_name)(session), payload)

            self.assertEqual(session["state"]["version"], 1)

    def test_scene_progress_setter_keeps_runtime_card_side_effects(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            service = DialogueService(Path(tmpdir) / "runs")
            session = {"participants": ["A", "B"]}

            service._set_session_scene_progress(
                session,
                {
                    "location": "garden",
                    "time_hint": "dusk",
                    "present_participants": ["A"],
                    "updated_at": "2026-01-02T03:04:05Z",
                },
            )

            snapshots = session["state"]["characters"]["snapshots"]
            self.assertEqual(snapshots["A"]["present_state"], "onstage")
            self.assertEqual(snapshots["B"]["present_state"], "offstage")
            self.assertEqual(snapshots["A"]["scene_location"], "garden")
            self.assertEqual(snapshots["A"]["updated_at"], "2026-01-02T03:04:05Z")


if __name__ == "__main__":
    unittest.main()
