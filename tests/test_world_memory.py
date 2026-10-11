from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from fastapi.testclient import TestClient

from src.web.chat.helpers import build_dialogue_llm_messages
from src.web.app import create_app
from src.web.chat.service import DialogueService
from src.web.chat.world_memory import WorldMemoryStore
from src.web.workflow import WebRunService


class WorldMemoryStoreTests(unittest.TestCase):
    def test_manual_fact_can_be_edited_locked_and_deleted(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_id = "run-1"
            (WorldMemoryStore(tmp).runs_root / run_id).mkdir()
            store = WorldMemoryStore(tmp)
            created = store.save_fact(
                run_id,
                fields={
                    "category": "commitment",
                    "summary": "甲答应在天亮前回来。",
                    "characters": ["甲"],
                    "locked": True,
                },
            )
            updated = store.save_fact(
                run_id,
                fact_id=created["fact_id"],
                fields={
                    "category": "commitment",
                    "summary": "甲答应在日出前回来。",
                    "characters": ["甲"],
                    "locked": True,
                    "active": True,
                },
            )

            self.assertTrue(updated["locked"])
            self.assertEqual(updated["summary"], "甲答应在日出前回来。")
            store.delete_fact(run_id, created["fact_id"])
            self.assertEqual(store.get(run_id)["facts"], [])

    def test_turn_sync_is_idempotent_and_builds_timeline(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_id = "run-1"
            store = WorldMemoryStore(tmp)
            (store.runs_root / run_id).mkdir()
            kwargs = {
                "session_id": "dlg-1",
                "turn_id": "turn-1",
                "title": "甲进入书房",
                "participants": ["甲", "乙"],
                "events": [
                    {
                        "kind": "cast_enter",
                        "actor": "甲",
                        "cue": "甲进入书房。",
                        "location_hint": "书房",
                    }
                ],
                "location": "书房",
                "time_hint": "夜晚",
                "consistency_status": "pass",
                "knowledge_ledger": [],
                "updated_at": "2026-07-30T12:00:00Z",
            }
            store.sync_completed_turn(run_id, **kwargs)
            store.sync_completed_turn(run_id, **kwargs)
            memory = store.get(run_id)

            self.assertEqual(len(memory["facts"]), 1)
            self.assertEqual(memory["facts"][0]["category"], "location")
            self.assertEqual(len(memory["timeline"]), 1)
            self.assertEqual(memory["timeline"][0]["location"], "书房")

    def test_relevant_facts_prioritize_locked_and_participant_facts(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_id = "run-1"
            store = WorldMemoryStore(tmp)
            (store.runs_root / run_id).mkdir()
            store.save_fact(run_id, fields={"summary": "无关旧事", "characters": ["丙"]})
            related = store.save_fact(run_id, fields={"summary": "甲持有钥匙", "characters": ["甲"]})
            locked = store.save_fact(run_id, fields={"summary": "世界永远是冬季", "locked": True})

            facts = store.relevant_facts(run_id, participants=["甲"], message="继续", limit=2)

            self.assertEqual({facts[0]["fact_id"], facts[1]["fact_id"]}, {related["fact_id"], locked["fact_id"]})

    def test_locked_world_fact_is_injected_and_completed_turn_is_timed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            persona_dir = root / "personas" / "甲"
            persona_dir.mkdir(parents=True)
            profile_path = persona_dir / "PROFILE.md"
            profile_path.write_text(
                "# PROFILE\n- name: 甲\n- core_identity: 测试人物\n",
                encoding="utf-8",
            )
            manifest = {
                "run_id": "run-memory",
                "novel_id": "novel-1",
                "artifact_index": {
                    "characters": [
                        {
                            "name": "甲",
                            "profile_file": str(profile_path),
                            "persona_dir": str(persona_dir),
                        }
                    ]
                },
            }
            dialogue = DialogueService(root / "runs")
            session = dialogue.create_session(manifest, mode="observe", participants=["甲"])
            dialogue.save_world_fact(
                "run-memory",
                fields={"summary": "世界永远是冬季。", "locked": True},
            )
            prepared = dialogue.prepare_turn(
                manifest,
                session_id=session["session_id"],
                message="继续。",
            )
            raw = dialogue._read_json(
                dialogue._session_file("run-memory", session["session_id"])
            )
            payload = dialogue._read_pending_turn_payload(
                "run-memory", session["session_id"], raw["pending_turn"]
            )

            self.assertEqual(
                payload["memory_context"]["world_facts"][0]["summary"],
                "世界永远是冬季。",
            )
            llm_messages = build_dialogue_llm_messages(payload)
            self.assertIn("WORLD_FACTS", llm_messages[1]["content"])
            self.assertIn("世界永远是冬季。", llm_messages[2]["content"])
            dialogue.ingest_turn_responses(
                "run-memory",
                session_id=session["session_id"],
                responses=[{"speaker": "甲", "message": "雪还没有停。"}],
            )
            memory = dialogue.get_world_memory("run-memory")
            self.assertEqual(len(memory["timeline"]), 1)
            self.assertEqual(
                memory["timeline"][0]["source_turn_id"],
                prepared["pending_turn_summary"]["turn_id"],
            )

    def test_completed_turn_only_archives_events_from_that_turn(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            persona_dir = root / "personas" / "A"
            persona_dir.mkdir(parents=True)
            profile_path = persona_dir / "PROFILE.md"
            profile_path.write_text("# PROFILE\n- name: A\n", encoding="utf-8")
            manifest = {
                "run_id": "run-events",
                "novel_id": "novel-1",
                "artifact_index": {
                    "characters": [{"name": "A", "profile_file": str(profile_path)}]
                },
            }
            dialogue = DialogueService(root / "runs")
            session = dialogue.create_session(manifest, mode="observe", participants=["A"])
            prepared = dialogue.prepare_turn(
                manifest, session_id=session["session_id"], message="continue"
            )
            turn_id = prepared["pending_turn_summary"]["turn_id"]
            raw_session = dialogue._read_json(
                dialogue._session_file("run-events", session["session_id"])
            )
            dialogue._set_session_event_signals(
                raw_session,
                dialogue._merge_event_signals_state(
                    raw_session,
                    [
                        {"kind": "environment_change", "cue": "old event", "turn_id": "old-turn"},
                        {"kind": "environment_change", "cue": "current event", "turn_id": turn_id},
                    ],
                ),
            )
            dialogue._write_json(
                dialogue._session_file("run-events", session["session_id"]), raw_session
            )

            dialogue.ingest_turn_responses(
                "run-events",
                session_id=session["session_id"],
                responses=[{"speaker": "A", "message": "acknowledged"}],
            )

            facts = dialogue.get_world_memory("run-events")["facts"]
            self.assertEqual([fact["summary"] for fact in facts], ["current event"])
            self.assertEqual(facts[0]["source_turn_id"], turn_id)

    def test_world_memory_routes_create_update_and_delete_fact(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            run_dir = service.runs_root / "run-api"
            run_dir.mkdir(parents=True)
            service._write_json(
                run_dir / "run_manifest.json",
                {"run_id": "run-api", "status": "ready"},
            )
            client = TestClient(create_app(service))

            created = client.post(
                "/api/web/runs/run-api/world-memory/facts",
                json={"category": "status", "summary": "甲受伤了", "characters": ["甲"]},
            )
            self.assertEqual(created.status_code, 200)
            fact_id = created.json()["fact_id"]
            updated = client.put(
                f"/api/web/runs/run-api/world-memory/facts/{fact_id}",
                json={
                    "category": "status",
                    "summary": "甲的伤已经痊愈",
                    "characters": ["甲"],
                    "locked": True,
                },
            )
            self.assertTrue(updated.json()["locked"])
            self.assertEqual(client.get("/api/web/runs/run-api/world-memory").status_code, 200)
            self.assertEqual(
                client.delete(
                    f"/api/web/runs/run-api/world-memory/facts/{fact_id}"
                ).status_code,
                200,
            )


if __name__ == "__main__":
    unittest.main()
