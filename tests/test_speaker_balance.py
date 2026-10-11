from __future__ import annotations

import json
import unittest

from src.web.chat.helpers import build_dialogue_llm_messages
from src.web.chat.speaker_balance import (
    apply_plan_to_hints,
    build_speaker_activity,
    build_speaker_plan,
    extract_mention_targets,
)


class SpeakerBalanceTests(unittest.TestCase):
    def setUp(self):
        self.turns = [
            {
                "result": {
                    "responses": [
                        {"speaker": "甲", "message": "一"},
                        {"speaker": "乙", "message": "一"},
                    ]
                }
            },
            {"result": {"responses": [{"speaker": "甲", "message": "二"}]}},
            {"result": {"responses": [{"speaker": "甲", "message": "三"}]}},
            {"result": {"responses": [{"speaker": "甲", "message": "四"}]}},
        ]

    def test_activity_marks_long_silence(self):
        activity = build_speaker_activity(["甲", "乙", "丙"], self.turns)
        by_name = {item["name"]: item for item in activity}

        self.assertEqual(by_name["甲"]["status"], "active")
        self.assertEqual(by_name["乙"]["turns_since_spoke"], 3)
        self.assertEqual(by_name["乙"]["status"], "silent")
        self.assertEqual(by_name["丙"]["turns_since_spoke"], 4)
        self.assertEqual(by_name["丙"]["status"], "silent")

    def test_direct_mention_wins_and_controlled_speaker_is_excluded(self):
        activity = build_speaker_activity(["甲", "乙", "丙"], self.turns)
        plan = build_speaker_plan(
            activity=activity,
            active_participants=["甲", "乙", "丙"],
            message="乙，你怎么看？",
            mode="act",
            input_speaker="甲",
            controlled_character="甲",
            message_kind="dialogue",
            response_limit=2,
        )

        self.assertEqual(plan["order"][0], "乙")
        self.assertNotIn("甲", plan["order"])
        self.assertEqual(plan["recommended_speakers"], ["乙", "丙"])
        self.assertIn("乙", plan["priority_candidates"])

    def test_offstage_character_never_enters_plan(self):
        activity = build_speaker_activity(["甲", "乙", "丙"], self.turns)
        plan = build_speaker_plan(
            activity=activity,
            active_participants=["甲", "乙"],
            message="继续",
            mode="observe",
            input_speaker="User",
            controlled_character="",
            message_kind="dialogue",
            response_limit=2,
        )

        self.assertNotIn("丙", plan["order"])

    def test_at_mention_is_urgent_and_matches_the_exact_present_name(self):
        activity = build_speaker_activity(["林", "林黛玉", "贾宝玉"], self.turns)
        plan = build_speaker_plan(
            activity=activity,
            active_participants=["林", "林黛玉", "贾宝玉"],
            message="@林黛玉 你怎么看？",
            mode="observe",
            input_speaker="User",
            controlled_character="",
            message_kind="dialogue",
            response_limit=2,
        )

        self.assertEqual(extract_mention_targets(["林", "林黛玉"], "@林黛玉,你说呢"), ["林黛玉"])
        self.assertEqual(
            extract_mention_targets(
                ["John", "John Doe"], "@John Doe, what do you think?"
            ),
            ["John Doe"],
        )
        self.assertEqual(plan["mention_targets"], ["林黛玉"])
        self.assertEqual(plan["order"][0], "林黛玉")
        self.assertIn("林黛玉", plan["priority_candidates"])
        self.assertIn("必须在本轮直接回应", plan["rule"])

        self_plan = build_speaker_plan(
            activity=activity,
            active_participants=["林", "林黛玉"],
            message="@林 你自己答。",
            mode="act",
            input_speaker="林",
            controlled_character="林",
            message_kind="dialogue",
            response_limit=2,
        )
        self.assertEqual(self_plan["mention_targets"], [])

    def test_plan_reorders_hints_and_reaches_final_prompt(self):
        activity = build_speaker_activity(["甲", "乙", "丙"], self.turns)
        plan = build_speaker_plan(
            activity=activity,
            active_participants=["甲", "乙", "丙"],
            message="继续",
            mode="observe",
            input_speaker="User",
            controlled_character="",
            message_kind="dialogue",
            response_limit=2,
        )
        hints = apply_plan_to_hints(
            [
                {"name": "甲", "should_reply": "yes", "priority": "normal"},
                {"name": "乙", "should_reply": "yes", "priority": "normal"},
                {"name": "丙", "should_reply": "yes", "priority": "normal"},
            ],
            plan,
        )
        payload = {
            "mode": "observe",
            "input": {
                "speaker": "User",
                "message": "继续",
                "participants": ["甲", "乙", "丙"],
                "active_participants": ["甲", "乙", "丙"],
            },
            "instructions": {"group_chat_rule": plan["rule"]},
            "host_action": {"response_limit_hint": 2},
            "speaker_plan": plan,
            "speaker_activity": activity,
            "responder_hints": hints,
        }

        messages = build_dialogue_llm_messages(payload)
        user_payload = json.loads(messages[-1]["content"])

        urgent_hint = next(item for item in hints if item["name"] == "丙")
        self.assertEqual(urgent_hint["priority"], "urgent")
        self.assertEqual(user_payload["speaker_plan"]["order"][0], "丙")
        self.assertEqual(user_payload["responder_hints"][0]["name"], "甲")
        self.assertIn("SPEAKER_PLAN", messages[-2]["content"])


if __name__ == "__main__":
    unittest.main()
