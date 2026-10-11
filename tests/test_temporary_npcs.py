from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from src.web.chat.helpers import parse_dialogue_responses
from src.web.chat.service import DialogueService


class TemporaryNpcTests(unittest.TestCase):
    def _manifest(self) -> dict:
        return {
            "run_id": "run-npc",
            "novel_id": "novel-npc",
            "artifact_index": {
                "characters": [{"name": "Hero", "profile_file": "missing-profile.md"}]
            },
        }

    def test_parser_keeps_named_npc_only_when_explicitly_allowed(self):
        content = '[{"speaker":"Innkeeper","message":"The rain is getting worse."}]'
        self.assertEqual(
            parse_dialogue_responses(content, ["Hero", "__temporary_npc__"])[0]["speaker"],
            "Innkeeper",
        )
        with self.assertRaisesRegex(ValueError, "usable character responses"):
            parse_dialogue_responses(content, ["Hero"])

    def test_parser_rejects_controlled_speaker_and_limits_new_npcs(self):
        content = (
            '[{"speaker":"Hero","message":"I answer for the user."},'
            '{"speaker":"Innkeeper","message":"One room remains."},'
            '{"speaker":"Night Watchman","message":"Open the gate."},'
            '{"speaker":"Innkeeper","message":"Decide quickly."}]'
        )

        responses = parse_dialogue_responses(
            content,
            [
                "Companion",
                "__temporary_npc__",
                "__forbidden_speaker__:Hero",
            ],
            max_temporary_npcs=1,
        )

        self.assertEqual(
            [item["speaker"] for item in responses],
            ["Innkeeper", "Innkeeper"],
        )

    def test_new_npc_becomes_present_and_can_reply_on_the_next_turn(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            dialogue = DialogueService(Path(tmpdir) / "runs")
            manifest = self._manifest()
            created = dialogue.create_session(manifest, mode="observe", participants=["Hero"])
            session_id = created["session_id"]

            dialogue.prepare_turn(manifest, session_id=session_id, message="A stranger enters.")
            first = dialogue.ingest_turn_responses(
                "run-npc",
                session_id=session_id,
                responses=[{"speaker": "Innkeeper", "message": "We are closing soon."}],
            )
            self.assertEqual(first["participants"], ["Hero", "Innkeeper"])
            self.assertIn("Innkeeper", first["temporary_npcs"])

            dialogue.update_scene_progress_state("run-npc", session_id, {})
            dialogue.prepare_turn(manifest, session_id=session_id, message="Ask the innkeeper about a room.")
            payload = dialogue._read_pending_turn_payload("run-npc", session_id, dialogue._read_json(dialogue._session_file("run-npc", session_id))["pending_turn"])
            self.assertIn("Innkeeper", payload["input"]["active_participants"])
            second = dialogue.ingest_turn_responses(
                "run-npc",
                session_id=session_id,
                responses=[{"speaker": "Innkeeper", "message": "One room remains upstairs."}],
            )
            self.assertEqual(second["history"][-1]["speaker"], "Innkeeper")

    def test_plugin_generated_npc_is_added_with_profile_and_opening(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            dialogue = DialogueService(Path(tmpdir) / "runs")
            manifest = self._manifest()
            created = dialogue.create_session(
                manifest, mode="observe", participants=["Hero"]
            )
            session_id = created["session_id"]

            updated = dialogue.add_temporary_npc(
                "run-npc",
                session_id,
                {
                    "name": "Night Watchman",
                    "role": "watchman",
                    "appearance": "a rain-soaked lantern",
                    "personality": "suspicious",
                    "speech_style": "short questions",
                    "motive": "find a fugitive",
                    "entrance": "The side door opens.",
                    "opening_line": "Who left through the back gate?",
                },
            )

            self.assertEqual(updated["participants"], ["Hero", "Night Watchman"])
            self.assertEqual(
                updated["temporary_npcs"]["Night Watchman"]["motive"],
                "find a fugitive",
            )
            self.assertEqual(updated["transcript"][-1]["speaker"], "Night Watchman")
            self.assertIn(
                "Night Watchman",
                updated["scene_progress"]["present_participants"],
            )
            payload = dialogue.build_suggestion_payload(
                manifest, session_id=session_id
            )
            profile = next(
                item
                for item in payload["persona_contexts"]
                if item["name"] == "Night Watchman"
            )
            self.assertEqual(profile["profile"]["speech_style"], "short questions")

            with self.assertRaisesRegex(ValueError, "已经存在"):
                dialogue.add_temporary_npc(
                    "run-npc",
                    session_id,
                    {
                        "name": "night watchman",
                        "opening_line": "Again.",
                    },
                )

    def test_ingest_registers_only_one_new_npc(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            dialogue = DialogueService(Path(tmpdir) / "runs")
            manifest = self._manifest()
            created = dialogue.create_session(
                manifest,
                mode="act",
                participants=["Hero"],
                controlled_character="Hero",
            )
            session_id = created["session_id"]

            dialogue.prepare_turn(manifest, session_id=session_id, message="Continue.")
            completed = dialogue.ingest_turn_responses(
                "run-npc",
                session_id=session_id,
                responses=[
                    {"speaker": "Hero", "message": "I answer for the user."},
                    {"speaker": "Innkeeper", "message": "One room remains."},
                    {"speaker": "Night Watchman", "message": "Open the gate."},
                ],
            )

            self.assertEqual(completed["participants"], ["Hero", "Innkeeper"])
            self.assertNotIn(
                "Night Watchman",
                [item["speaker"] for item in completed["history"]],
            )

    def test_branch_restores_temporary_npc_from_target_checkpoint(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            dialogue = DialogueService(Path(tmpdir) / "runs")
            manifest = self._manifest()
            created = dialogue.create_session(
                manifest, mode="observe", participants=["Hero"]
            )
            session_id = created["session_id"]

            dialogue.prepare_turn(manifest, session_id=session_id, message="Someone enters.")
            completed = dialogue.ingest_turn_responses(
                "run-npc",
                session_id=session_id,
                responses=[{"speaker": "Innkeeper", "message": "We are closing."}],
            )
            turn_id = completed["event_timeline"][0]["turn_id"]

            branch = dialogue.branch_session_from_turn(
                manifest, session_id, turn_id=turn_id
            )

            self.assertEqual(branch["participants"], ["Hero", "Innkeeper"])
            self.assertIn("Innkeeper", branch["temporary_npcs"])

    def test_correction_branch_restores_npc_present_before_bad_turn(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            dialogue = DialogueService(Path(tmpdir) / "runs")
            manifest = self._manifest()
            created = dialogue.create_session(
                manifest,
                mode="act",
                participants=["Hero"],
                controlled_character="Hero",
            )
            session_id = created["session_id"]

            dialogue.prepare_turn(manifest, session_id=session_id, message="Enter.")
            dialogue.ingest_turn_responses(
                "run-npc",
                session_id=session_id,
                responses=[{"speaker": "Innkeeper", "message": "We are closing."}],
            )
            dialogue.prepare_turn(manifest, session_id=session_id, message="Continue.")
            completed = dialogue.ingest_turn_responses(
                "run-npc",
                session_id=session_id,
                responses=[{"speaker": "Hero", "message": "I answer for the user."}],
            )
            self.assertTrue(completed["consistency_monitor"]["latest"]["issues"])

            branch, _correction = dialogue.create_correction_branch(
                manifest, session_id
            )

            self.assertEqual(branch["participants"], ["Hero", "Innkeeper"])
            self.assertIn("Innkeeper", branch["temporary_npcs"])

    def test_temporary_npc_is_not_written_to_run_world_timeline(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            dialogue = DialogueService(Path(tmpdir) / "runs")
            manifest = self._manifest()
            created = dialogue.create_session(
                manifest, mode="observe", participants=["Hero"]
            )
            session_id = created["session_id"]

            dialogue.prepare_turn(
                manifest,
                session_id=session_id,
                message="Ask Innkeeper about the road.",
            )
            stored = dialogue._read_json(
                dialogue._session_file("run-npc", session_id)
            )
            turn_id = stored["pending_turn"]["turn_id"]
            dialogue._set_session_event_signals(
                stored,
                {
                    "recent": [
                        {
                            "turn_id": turn_id,
                            "kind": "cast_enter",
                            "actor": "Innkeeper",
                            "cue": "Innkeeper enters through the side door.",
                        }
                    ]
                },
            )
            dialogue._write_json(
                dialogue._session_file("run-npc", session_id), stored
            )
            dialogue.ingest_turn_responses(
                "run-npc",
                session_id=session_id,
                responses=[{"speaker": "Innkeeper", "message": "The road is closed."}],
            )
            memory = dialogue.get_world_memory("run-npc")

            self.assertEqual(memory["timeline"][0]["participants"], ["Hero"])
            self.assertNotIn("Innkeeper", memory["timeline"][0]["title"])
            self.assertFalse(
                any(
                    "Innkeeper" in str(item.get("summary", ""))
                    for item in memory["facts"]
                )
            )
