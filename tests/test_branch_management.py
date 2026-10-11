from __future__ import annotations

import tempfile
import unittest
from pathlib import Path


class DialogueBranchManagementTests(unittest.TestCase):
    def test_branch_tree_metadata_mainline_event_lock_and_relation_comparison(self):
        try:
            from src.web.chat.service import DialogueService
        except ModuleNotFoundError as exc:
            self.skipTest(f"optional runtime dependency unavailable: {exc}")

        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            characters = []
            for name in ("A", "B"):
                persona_dir = root / "personas" / name
                persona_dir.mkdir(parents=True, exist_ok=True)
                profile_path = persona_dir / "PROFILE.md"
                profile_path.write_text(
                    f"# PROFILE\n- name: {name}\n- core_identity: test character\n",
                    encoding="utf-8",
                )
                characters.append(
                    {
                        "name": name,
                        "profile_file": str(profile_path),
                        "persona_dir": str(persona_dir),
                    }
                )
            manifest = {
                "run_id": "run-branches",
                "novel_id": "novel-1",
                "artifact_index": {"characters": characters},
            }
            dialogue = DialogueService(root / "runs")
            created = dialogue.create_session(
                manifest,
                mode="observe",
                participants=["A", "B"],
            )
            source_id = created["session_id"]
            self.assertTrue(created["branch_meta"]["is_mainline"])

            dialogue.prepare_turn(
                manifest,
                session_id=source_id,
                message="Choose the left door.",
            )
            completed = dialogue.ingest_turn_responses(
                "run-branches",
                session_id=source_id,
                responses=[{"speaker": "A", "message": "I will follow."}],
            )
            turn_id = completed["event_timeline"][0]["turn_id"]
            locked = dialogue.update_branch_metadata(
                "run-branches",
                source_id,
                label="Original route",
                locked_event_ids=[turn_id],
            )
            self.assertTrue(locked["event_timeline"][0]["is_mainline_anchor"])

            branch = dialogue.branch_session_from_turn(
                manifest,
                source_id,
                turn_id=turn_id,
            )
            branch_id = branch["session_id"]
            self.assertEqual(branch["branch_meta"]["locked_event_ids"], [turn_id])
            self.assertEqual(len(branch["branch_graph"]["nodes"]), 2)

            pair_key = dialogue._pair_key("A", "B")
            branch_path = dialogue._session_file("run-branches", branch_id)
            branch_raw = dialogue._read_json(branch_path)
            matrix = dialogue._merged_relation_matrix(
                branch_raw, list(branch_raw.get("participants", []) or [])
            )
            matrix[pair_key]["trust"] = 8
            dialogue._set_session_relation_matrix(branch_raw, matrix)
            dialogue._write_json(branch_path, branch_raw)

            updated = dialogue.update_branch_metadata(
                "run-branches",
                branch_id,
                label="Trust route",
                is_mainline=True,
            )
            nodes = {
                item["session_id"]: item for item in updated["branch_graph"]["nodes"]
            }
            self.assertEqual(nodes[branch_id]["label"], "Trust route")
            self.assertTrue(nodes[branch_id]["is_mainline"])
            self.assertFalse(nodes[source_id]["is_mainline"])
            trust_change = next(
                item
                for item in nodes[source_id]["relation_changes"]
                if item["pair_key"] == pair_key and item["metric"] == "trust"
            )
            self.assertEqual(trust_change["delta"], -3)

            with self.assertRaises(ValueError):
                dialogue.update_branch_metadata(
                    "run-branches",
                    branch_id,
                    locked_event_ids=["turn-missing"],
                )

    def test_legacy_turn_branch_uses_next_prompt_history_after_live_compression(self):
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
                "run_id": "run-legacy-branch",
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
                message="First choice.",
            )
            first = dialogue.ingest_turn_responses(
                "run-legacy-branch",
                session_id=session_id,
                responses=[{"speaker": "A", "message": "First reply."}],
            )
            first_turn_id = first["event_timeline"][0]["turn_id"]

            dialogue.prepare_turn(
                manifest,
                session_id=session_id,
                message="Second choice.",
            )
            second = dialogue.ingest_turn_responses(
                "run-legacy-branch",
                session_id=session_id,
                responses=[{"speaker": "A", "message": "Second reply."}],
            )
            second_turn_id = second["event_timeline"][-1]["turn_id"]

            first_result_path = dialogue._turn_file(
                "run-legacy-branch", session_id, first_turn_id, "result"
            )
            first_result = dialogue._read_json(first_result_path)
            first_result.pop("checkpoint", None)
            dialogue._write_json(first_result_path, first_result)

            second_payload_path = dialogue._turn_file(
                "run-legacy-branch", session_id, second_turn_id, "payload"
            )
            second_payload = dialogue._read_json(second_payload_path)
            second_payload.pop("checkpoint_before", None)
            dialogue._write_json(second_payload_path, second_payload)

            source_path = dialogue._session_file("run-legacy-branch", session_id)
            source = dialogue._read_json(source_path)
            source["history"] = list(source.get("history", []) or [])[-2:]
            dialogue._write_json(source_path, source)
            self.assertNotIn(
                "First reply.",
                [item["message"] for item in source["history"]],
            )

            branch = dialogue.branch_session_from_turn(
                manifest,
                session_id,
                turn_id=first_turn_id,
            )

            self.assertEqual(
                [item["message"] for item in branch["history"]],
                ["First choice.", "First reply."],
            )
            self.assertNotIn(
                "Second reply.",
                [item["message"] for item in branch["history"]],
            )


if __name__ == "__main__":
    unittest.main()
