from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from src.web.chat.consistency import (
    build_monitor_metrics,
    evaluate_turn_consistency,
    merge_semantic_review,
    update_knowledge_ledger,
    update_monitor_state,
)
from src.web.chat.helpers import (
    build_dialogue_consistency_review_messages,
    build_dialogue_llm_messages,
    parse_dialogue_consistency_review,
)


class ChatConsistencyTests(unittest.TestCase):
    def _payload(self) -> dict:
        return {
            "turn_id": "turn-1",
            "mode": "act",
            "input": {
                "participants": ["甲", "乙"],
                "active_participants": ["乙"],
                "controlled_character": "甲",
                "scene_progress": {"offstage_participants": ["乙"]},
            },
            "scene_progress": {"offstage_participants": ["乙"]},
            "persona_contexts": [
                {
                    "name": "乙",
                    "profile": {"forbidden_behaviors": ["主动伤害无辜者"]},
                }
            ],
        }

    def test_detects_scope_presence_control_and_forbidden_behavior(self):
        report = evaluate_turn_consistency(
            self._payload(),
            [
                {"speaker": "甲", "message": "让我替你回答。"},
                {"speaker": "乙", "message": "我会主动伤害无辜者。"},
                {"speaker": "丙", "message": "我也来了。"},
            ],
            checked_at="2026-07-21T00:00:00Z",
        )
        self.assertEqual(report["status"], "warning")
        self.assertEqual(
            {item["code"] for item in report["issues"]},
            {
                "controlled_character_overwritten",
                "offstage_character_spoke",
                "forbidden_behavior_overlap",
                "speaker_out_of_scope",
            },
        )
        self.assertFalse(report["coverage"]["semantic_review"])

    def test_negated_forbidden_behavior_is_not_flagged(self):
        report = evaluate_turn_consistency(
            self._payload(),
            [{"speaker": "乙", "message": "我绝不会主动伤害无辜者。"}],
            checked_at="now",
        )
        codes = {item["code"] for item in report["issues"]}
        self.assertNotIn("forbidden_behavior_overlap", codes)
        self.assertIn("offstage_character_spoke", codes)

    def test_director_beat_may_include_the_controlled_character_reaction(self):
        payload = self._payload()
        payload["input"]["message_kind"] = "plot"
        report = evaluate_turn_consistency(
            payload,
            [{"speaker": "甲", "message": "（抬头看向门外）是谁来了？"}],
            checked_at="now",
        )

        codes = {item["code"] for item in report["issues"]}
        self.assertNotIn("controlled_character_overwritten", codes)

    def test_monitor_state_keeps_bounded_history_and_totals(self):
        state = {}
        for index in range(25):
            state = update_monitor_state(
                state,
                {
                    "turn_id": f"turn-{index}",
                    "issues": ([{"code": "x"}] if index % 2 == 0 else []),
                },
            )
        self.assertEqual(state["checked_turns"], 25)
        self.assertEqual(state["issue_count"], 13)
        self.assertEqual(len(state["history"]), 20)
        self.assertEqual(state["latest"]["turn_id"], "turn-24")

    def test_monitor_metrics_summarize_pass_rate_streak_and_categories(self):
        metrics = build_monitor_metrics(
            [
                {
                    "turn_id": "turn-1",
                    "status": "warning",
                    "score": 75,
                    "issues": [{"code": "offstage_character_spoke"}],
                },
                {
                    "turn_id": "turn-2",
                    "status": "pass",
                    "score": 100,
                    "issues": [],
                },
                {
                    "turn_id": "turn-3",
                    "status": "pass",
                    "score": 100,
                    "issues": [],
                },
            ]
        )

        self.assertEqual(metrics["average_score"], 92)
        self.assertEqual(metrics["pass_rate"], 67)
        self.assertEqual(metrics["current_pass_streak"], 2)
        self.assertEqual(metrics["category_counts"], {"scene_continuity": 1})

    def test_semantic_review_parser_requires_evidence_from_the_response(self):
        content = json.dumps(
            {
                "issues": [
                    {
                        "code": "semantic_voice_drift",
                        "severity": "warning",
                        "speaker": "甲",
                        "title": "语气突变",
                        "detail": "突然使用不符合人物身份的措辞",
                        "evidence": "完全没问题",
                    },
                    {
                        "code": "semantic_motivation_drift",
                        "severity": "error",
                        "speaker": "甲",
                        "title": "动机突变",
                        "detail": "无铺垫地放弃目标",
                        "evidence": "我决定放弃",
                    },
                ],
                "summary": "发现一项明确问题",
            },
            ensure_ascii=False,
        )
        review = parse_dialogue_consistency_review(
            content,
            responses=[{"speaker": "甲", "message": "我决定放弃，不再追问。"}],
            allowed_speakers=["甲"],
        )

        self.assertEqual(len(review["issues"]), 1)
        self.assertEqual(review["issues"][0]["code"], "semantic_motivation_drift")
        self.assertEqual(review["issues"][0]["source"], "semantic_review")

    def test_semantic_review_replaces_previous_semantic_findings(self):
        report = {
            "turn_id": "turn-1",
            "status": "warning",
            "score": 65,
            "issues": [
                {"code": "offstage_character_spoke", "severity": "error"},
                {
                    "code": "semantic_voice_drift",
                    "severity": "warning",
                    "source": "semantic_review",
                },
            ],
            "coverage": {"semantic_review": True},
        }
        merged = merge_semantic_review(
            report,
            {
                "issues": [
                    {
                        "code": "semantic_relationship_drift",
                        "severity": "warning",
                        "speaker": "甲",
                        "evidence": "随便你",
                    }
                ]
            },
            reviewed_at="now",
        )

        self.assertEqual(
            {item["code"] for item in merged["issues"]},
            {"offstage_character_spoke", "semantic_relationship_drift"},
        )
        self.assertEqual(merged["score"], 65)
        self.assertEqual(merged["coverage"]["semantic_reviewed_at"], "now")

    def test_records_secret_holders_and_flags_outsider_knowledge(self):
        disclosure = {
            "turn_id": "turn-secret",
            "mode": "observe",
            "input": {
                "speaker": "甲",
                "message": "秘密是钥匙藏在东厢房梁上。",
                "participants": ["甲", "乙", "丙"],
                "active_participants": ["甲", "乙"],
            },
            "scene_progress": {
                "present_participants": ["甲", "乙"],
                "offstage_participants": ["丙"],
            },
        }
        ledger = update_knowledge_ledger(
            [], disclosure, [], recorded_at="2026-07-21T00:00:00Z"
        )

        self.assertEqual(ledger[0]["holders"], ["甲", "乙"])
        follow_up = {
            **disclosure,
            "turn_id": "turn-follow-up",
            "knowledge_context": ledger,
            "scene_progress": {
                "present_participants": ["甲", "乙", "丙"],
                "offstage_participants": [],
            },
        }
        report = evaluate_turn_consistency(
            follow_up,
            [{"speaker": "丙", "message": "我去东厢房梁上取钥匙。"}],
            checked_at="now",
        )

        self.assertIn(
            "knowledge_boundary_violation",
            {item["code"] for item in report["issues"]},
        )

    def test_latest_exit_event_is_checked_even_when_scene_state_is_stale(self):
        payload = {
            "turn_id": "turn-event",
            "mode": "observe",
            "input": {
                "participants": ["甲", "乙"],
                "character_snapshots": {},
            },
            "memory_context": {
                "event_signals": [
                    {"kind": "cast_enter", "actor": "乙", "cue": "乙进门"},
                    {"kind": "cast_exit", "actor": "乙", "cue": "乙告辞离开"},
                ]
            },
        }
        report = evaluate_turn_consistency(
            payload,
            [{"speaker": "乙", "message": "我还有一句话。"}],
            checked_at="now",
        )

        self.assertIn(
            "character_spoke_after_exit_event",
            {item["code"] for item in report["issues"]},
        )

    def test_dialogue_prompt_receives_knowledge_boundary(self):
        payload = {
            "mode": "observe",
            "input": {
                "speaker": "User",
                "message": "继续",
                "participants": ["甲", "乙", "丙"],
                "active_participants": ["丙"],
            },
            "knowledge_context": [
                {"fact": "钥匙藏在东厢房梁上", "holders": ["甲", "乙"]}
            ],
            "host_action": {"response_limit_hint": 1},
        }

        messages = build_dialogue_llm_messages(payload)
        user_payload = json.loads(messages[-1]["content"])

        self.assertEqual(
            user_payload["knowledge_boundary"][0]["holders"], ["甲", "乙"]
        )
        self.assertIn("KNOWLEDGE_BOUNDARY", messages[-2]["content"])

    def test_detects_character_location_mismatch_and_explicit_time_regression(self):
        payload = {
            "turn_id": "turn-place-time",
            "mode": "observe",
            "input": {
                "participants": ["甲"],
                "character_snapshots": {
                    "甲": {"scene_location": "前厅", "present_state": "onstage"}
                },
            },
            "scene_progress": {
                "location": "后园",
                "time_hint": "深夜",
                "present_participants": ["甲"],
            },
        }
        report = evaluate_turn_consistency(
            payload,
            [{"speaker": "甲", "message": "现在还是傍晚，天色尚早。"}],
            checked_at="now",
        )

        codes = {item["code"] for item in report["issues"]}
        self.assertIn("character_location_mismatch", codes)
        self.assertIn("time_regression_claim", codes)

    def test_correction_branch_rewinds_only_the_inconsistent_turn(self):
        try:
            from src.web.chat.service import DialogueService
        except ModuleNotFoundError as exc:
            self.skipTest(f"optional runtime dependency unavailable: {exc}")
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            character_items = []
            for name in ("甲", "乙"):
                persona_dir = root / "personas" / name
                persona_dir.mkdir(parents=True, exist_ok=True)
                profile_path = persona_dir / "PROFILE.md"
                profile_path.write_text(
                    f"# PROFILE\n- name: {name}\n- core_identity: 测试角色\n",
                    encoding="utf-8",
                )
                character_items.append(
                    {
                        "name": name,
                        "profile_file": str(profile_path),
                        "persona_dir": str(persona_dir),
                    }
                )
            dialogue = DialogueService(root / "runs")
            manifest = {
                "run_id": "run-1",
                "novel_id": "novel-1",
                "artifact_index": {"characters": character_items},
            }
            session = dialogue.create_session(
                manifest,
                mode="act",
                participants=["甲", "乙"],
                controlled_character="甲",
            )
            session_id = session["session_id"]
            dialogue.prepare_turn(
                manifest,
                session_id=session_id,
                message="先问乙。",
            )
            dialogue.ingest_turn_responses(
                "run-1",
                session_id=session_id,
                responses=[{"speaker": "乙", "message": "先说正事。"}],
            )
            session_path = dialogue._session_file("run-1", session_id)
            before_second_turn = dialogue._read_json(session_path)
            pair_key = dialogue._pair_key("甲", "乙")
            relation_matrix = dialogue._merged_relation_matrix(
                before_second_turn,
                list(before_second_turn.get("participants", []) or []),
            )
            relation_matrix[pair_key]["trust"] = 8
            dialogue._set_session_relation_matrix(
                before_second_turn, relation_matrix
            )
            dialogue._write_json(session_path, before_second_turn)
            dialogue.prepare_turn(
                manifest,
                session_id=session_id,
                message="你怎么看？",
            )
            completed = dialogue.ingest_turn_responses(
                "run-1",
                session_id=session_id,
                responses=[{"speaker": "甲", "message": "我替你回答。"}],
            )
            self.assertTrue(completed["consistency_monitor"]["latest"]["issues"])

            branch, correction = dialogue.create_correction_branch(
                manifest, session_id
            )

            self.assertNotEqual(branch["session_id"], session_id)
            self.assertEqual(
                [entry["message"] for entry in branch["history"]],
                ["先问乙。", "先说正事。"],
            )
            self.assertEqual(len(branch["event_timeline"]), 1)
            self.assertTrue(branch["event_timeline"][0]["inherited"])
            self.assertEqual(branch["relation_matrix"][pair_key]["trust"], 8)
            self.assertEqual(branch["consistency_monitor"]["checked_turns"], 1)
            self.assertEqual(correction["message"], "你怎么看？")
            self.assertEqual(
                correction["original_responses"][0]["message"], "我替你回答。"
            )
            review_payload = dialogue.build_consistency_review_payload(
                "run-1", session_id
            )
            self.assertEqual(
                review_payload["responses"][0]["message"], "我替你回答。"
            )
            with self.assertRaisesRegex(ValueError, "已过期"):
                dialogue.apply_semantic_consistency_review(
                    "run-1",
                    session_id=session_id,
                    review={"issues": []},
                    expected_turn_id="turn-stale",
                )
            reviewed = dialogue.apply_semantic_consistency_review(
                "run-1",
                session_id=session_id,
                review={
                    "issues": [
                        {
                            "code": "semantic_voice_drift",
                            "severity": "warning",
                            "speaker": "甲",
                            "title": "语气异常",
                            "detail": "测试",
                            "evidence": "替你回答",
                            "source": "semantic_review",
                        }
                    ]
                },
            )
            self.assertTrue(
                reviewed["consistency_monitor"]["latest"]["coverage"][
                    "semantic_review"
                ]
            )

    def test_event_timeline_branch_restores_selected_turn_checkpoint(self):
        try:
            from src.web.chat.service import DialogueService
        except ModuleNotFoundError as exc:
            self.skipTest(f"optional runtime dependency unavailable: {exc}")
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            persona_dir = root / "personas" / "甲"
            persona_dir.mkdir(parents=True, exist_ok=True)
            profile_path = persona_dir / "PROFILE.md"
            profile_path.write_text(
                "# PROFILE\n- name: 甲\n- core_identity: 测试角色\n",
                encoding="utf-8",
            )
            manifest = {
                "run_id": "run-timeline",
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
            created = dialogue.create_session(
                manifest,
                mode="observe",
                participants=["甲"],
            )
            session_id = created["session_id"]
            remembered = dialogue.upsert_controlled_memory(
                "run-timeline",
                session_id,
                text="甲答应在花厅等到天亮。",
                category="story",
                pinned=True,
            )
            self.assertTrue(remembered["memory_ledger"][0]["pinned"])
            dialogue.prepare_turn(manifest, session_id=session_id, message="第一步")
            first = dialogue.ingest_turn_responses(
                "run-timeline",
                session_id=session_id,
                responses=[{"speaker": "甲", "message": "先去花厅。"}],
            )
            first_turn_id = first["event_timeline"][0]["turn_id"]
            dialogue.update_scene_progress_state(
                "run-timeline",
                session_id,
                {"location": "花厅", "time_hint": "夜里"},
            )
            dialogue.prepare_turn(manifest, session_id=session_id, message="第二步")
            completed = dialogue.ingest_turn_responses(
                "run-timeline",
                session_id=session_id,
                responses=[{"speaker": "甲", "message": "再去后园。"}],
            )

            self.assertEqual(len(completed["event_timeline"]), 2)
            self.assertEqual(completed["event_timeline"][0]["title"], "第一步")
            self.assertEqual(completed["event_timeline"][0]["event_types"], ["dialogue"])
            self.assertEqual(completed["event_timeline"][0]["location"], "花厅")

            branch = dialogue.branch_session_from_turn(
                manifest,
                session_id,
                turn_id=first_turn_id,
            )

            self.assertNotEqual(branch["session_id"], session_id)
            self.assertEqual(branch["branch_origin"]["kind"], "event_timeline")
            self.assertEqual(
                [entry["message"] for entry in branch["history"]],
                ["第一步", "先去花厅。"],
            )
            self.assertEqual(branch["scene_progress"]["location"], "花厅")
            self.assertEqual(len(branch["event_timeline"]), 1)
            self.assertTrue(branch["event_timeline"][0]["inherited"])
            self.assertTrue(branch["event_timeline"][0]["can_branch"])
            self.assertEqual(
                branch["memory_ledger"][0]["text"], "甲答应在花厅等到天亮。"
            )

            repeated_branch = dialogue.branch_session_from_turn(
                manifest,
                branch["session_id"],
                turn_id=first_turn_id,
            )
            self.assertEqual(
                [entry["message"] for entry in repeated_branch["history"]],
                ["第一步", "先去花厅。"],
            )
            source = dialogue.get_session("run-timeline", session_id)
            self.assertEqual(len(source["history"]), 4)

    def test_controlled_memory_crud_prompt_injection_and_usage_audit(self):
        try:
            from src.web.chat.service import DialogueService
        except ModuleNotFoundError as exc:
            self.skipTest(f"optional runtime dependency unavailable: {exc}")
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            persona_dir = root / "personas" / "甲"
            persona_dir.mkdir(parents=True, exist_ok=True)
            profile_path = persona_dir / "PROFILE.md"
            profile_path.write_text(
                "# PROFILE\n- name: 甲\n- core_identity: 测试角色\n",
                encoding="utf-8",
            )
            manifest = {
                "run_id": "run-memory-control",
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
            created = dialogue.create_session(
                manifest, mode="observe", participants=["甲"]
            )
            session_id = created["session_id"]
            pinned = dialogue.upsert_controlled_memory(
                "run-memory-control",
                session_id,
                text="甲绝不能泄露密函。",
                category="long_term",
                pinned=True,
            )["memory_ledger"][0]
            disabled = dialogue.upsert_controlled_memory(
                "run-memory-control",
                session_id,
                text="这条记忆暂时不用。",
                category="short_term",
                enabled=False,
            )["memory_ledger"][1]

            prepared = dialogue.prepare_turn(
                manifest,
                session_id=session_id,
                message="继续",
            )
            turn_id = prepared["pending_turn_summary"]["turn_id"]
            turn_payload = dialogue._read_json(
                dialogue._session_dir("run-memory-control", session_id)
                / "turns"
                / f"{turn_id}.payload.json"
            )
            controlled = turn_payload["memory_context"]["controlled_memories"]
            self.assertEqual(len(controlled), 1)
            self.assertEqual(controlled[0]["text"], "甲绝不能泄露密函。")
            self.assertTrue(controlled[0]["pinned"])

            messages = build_dialogue_llm_messages(turn_payload)
            user_payload = json.loads(messages[-1]["content"])
            self.assertEqual(
                user_payload["memory_context"]["controlled_memories"][0]["text"],
                "甲绝不能泄露密函。",
            )
            self.assertIn("CONTROLLED_MEMORIES", messages[-2]["content"])

            completed = dialogue.ingest_turn_responses(
                "run-memory-control",
                session_id=session_id,
                responses=[{"speaker": "甲", "message": "我不会说。"}],
            )
            controlled_usage = next(
                item
                for item in completed["latest_context_usage"]["sources"]
                if item["kind"] == "controlled"
            )
            self.assertEqual(controlled_usage["count"], 1)
            self.assertIn("甲绝不能泄露密函", controlled_usage["items"][0])

            updated = dialogue.upsert_controlled_memory(
                "run-memory-control",
                session_id,
                memory_id=pinned["memory_id"],
                text="甲可以向乙展示密函，但不能交出去。",
                category="relationship",
                pinned=False,
                enabled=True,
            )
            self.assertEqual(updated["memory_ledger"][0]["category"], "relationship")
            deleted = dialogue.delete_controlled_memory(
                "run-memory-control",
                session_id,
                memory_id=disabled["memory_id"],
            )
            self.assertEqual(len(deleted["memory_ledger"]), 1)
            with self.assertRaises(ValueError):
                dialogue.delete_controlled_memory(
                    "run-memory-control",
                    session_id,
                    memory_id="mem-missing",
                )

    def test_relation_timeline_explains_metric_change_and_persists_lock(self):
        try:
            from src.web.chat.service import DialogueService
        except ModuleNotFoundError as exc:
            self.skipTest(f"optional runtime dependency unavailable: {exc}")
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            character_items = []
            for name in ("甲", "乙"):
                persona_dir = root / "personas" / name
                persona_dir.mkdir(parents=True, exist_ok=True)
                profile_path = persona_dir / "PROFILE.md"
                profile_path.write_text(
                    f"# PROFILE\n- name: {name}\n- core_identity: 测试角色\n",
                    encoding="utf-8",
                )
                character_items.append(
                    {
                        "name": name,
                        "profile_file": str(profile_path),
                        "persona_dir": str(persona_dir),
                    }
                )
            manifest = {
                "run_id": "run-relation-timeline",
                "novel_id": "novel-1",
                "artifact_index": {"characters": character_items},
            }
            dialogue = DialogueService(root / "runs")
            created = dialogue.create_session(
                manifest,
                mode="observe",
                participants=["甲", "乙"],
                scene_profile={
                    "scene_card_id": "scene-1",
                    "title": "前厅",
                    "location": "前厅",
                },
            )
            session_id = created["session_id"]
            pair_key = dialogue._pair_key("甲", "乙")
            session_path = dialogue._session_file("run-relation-timeline", session_id)
            raw = dialogue._read_json(session_path)
            dialogue._set_session_relation_delta(
                raw,
                {
                    pair_key: {
                        "trust": 2,
                        "last_event": "乙选择相信甲的解释。",
                        "evidence_lines": ["乙->甲：这一次我信你。"],
                    }
                },
            )
            dialogue._write_json(session_path, raw)
            locked = dialogue.set_relation_lock(
                "run-relation-timeline",
                session_id,
                pair_key=pair_key,
                locked=True,
            )
            self.assertTrue(locked["relation_locks"][pair_key])

            from src.web.service_facades.dialogue import DialogueServiceMixin

            facade = DialogueServiceMixin()
            facade.dialogue = dialogue
            facade._evolve_relations_from_turn(
                "run-relation-timeline",
                {
                    "session_id": session_id,
                    "input": {
                        "speaker": "甲",
                        "message": "你还信我吗？",
                        "message_kind": "dialogue",
                        "participants": ["甲", "乙"],
                        "active_participants": ["甲", "乙"],
                    },
                },
                [{"speaker": "乙", "message": "我不信，别再骗我。"}],
                refine_with_llm=False,
            )
            locked_raw = dialogue._read_json(session_path)
            self.assertEqual(
                dialogue._session_relation_delta(locked_raw)[pair_key]["trust"],
                2,
            )
            self.assertEqual(
                dialogue._session_relation_delta(locked_raw)[pair_key]["last_event"],
                "乙选择相信甲的解释。",
            )

            dialogue.prepare_turn(
                manifest,
                session_id=session_id,
                message="继续",
            )
            completed = dialogue.ingest_turn_responses(
                "run-relation-timeline",
                session_id=session_id,
                responses=[{"speaker": "乙", "message": "这一次我信你。"}],
            )

            timeline = completed["relation_timeline"][0]
            self.assertEqual(len(completed["relation_timeline"]), 1)
            self.assertTrue(timeline["locked"])
            self.assertEqual(timeline["current"]["trust"], 7)
            self.assertEqual(timeline["points"][1]["changes"]["trust"], 2)
            self.assertEqual(
                timeline["points"][1]["reason"], "乙选择相信甲的解释。"
            )
            self.assertIn("这一次我信你", timeline["points"][1]["evidence"])

            turn_id = completed["event_timeline"][0]["turn_id"]
            event_branch = dialogue.branch_session_from_turn(
                manifest,
                session_id,
                turn_id=turn_id,
            )
            self.assertTrue(event_branch["relation_locks"][pair_key])
            self.assertEqual(len(event_branch["relation_timeline"][0]["points"]), 2)
            self.assertEqual(
                event_branch["relation_timeline"][0]["current"]["trust"], 7
            )

            scene_branch = dialogue.branch_session_from_scene(
                manifest,
                session_id,
                scene_index=0,
            )
            self.assertTrue(scene_branch["relation_locks"][pair_key])

            unlocked = dialogue.set_relation_lock(
                "run-relation-timeline",
                session_id,
                pair_key=pair_key,
                locked=False,
            )
            self.assertFalse(unlocked["relation_timeline"][0]["locked"])
            with self.assertRaises(ValueError):
                dialogue.set_relation_lock(
                    "run-relation-timeline",
                    session_id,
                    pair_key="甲_不存在",
                    locked=True,
                )

    def test_dialogue_prompt_receives_correction_context(self):
        payload = {
            "mode": "observe",
            "input": {
                "speaker": "User",
                "message": "继续",
                "participants": ["甲"],
                "active_participants": ["甲"],
            },
            "correction_context": {
                "issues": [{"code": "offstage_character_spoke"}],
                "original_responses": [{"speaker": "甲", "message": "旧回复"}],
            },
            "host_action": {"response_limit_hint": 1},
        }

        messages = build_dialogue_llm_messages(payload)
        user_payload = json.loads(messages[-1]["content"])

        self.assertEqual(
            user_payload["correction_context"]["original_responses"][0]["message"],
            "旧回复",
        )
        self.assertIn("CORRECTION_CONTEXT", messages[-2]["content"])

    def test_deep_review_prompt_contains_semantic_dimensions(self):
        messages = build_dialogue_consistency_review_messages(
            {
                "participants": ["甲"],
                "responses": [{"speaker": "甲", "message": "随便你。"}],
            }
        )

        self.assertIn("semantic_voice_drift", messages[0]["content"])
        self.assertIn("关系态度", messages[0]["content"])
        self.assertIn("随便你", messages[1]["content"])


if __name__ == "__main__":
    unittest.main()
