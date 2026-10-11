from __future__ import annotations

import json
import unittest

from src.web.chat.helpers import (
    build_dialogue_director_llm_messages,
    parse_dialogue_director_options,
)


class DirectorModeTests(unittest.TestCase):
    def test_director_prompt_carries_goal_action_and_current_state(self):
        payload = {
            "mode": "observe",
            "director_goal": "让两人从误会走向和解，但不要立刻说破。",
            "director_action": "slow_emotion",
            "option_count": 3,
            "input": {
                "participants": ["甲", "乙", "丙"],
                "active_participants": ["甲", "乙"],
            },
            "scene_progress": {
                "location": "花厅",
                "offstage_participants": ["丙"],
            },
            "latest_exchange": {
                "replies": [{"speaker": "乙", "message": "我不是不信你。"}]
            },
            "speaker_activity": [{"name": "甲", "status": "silent"}],
        }

        messages = build_dialogue_director_llm_messages(payload)
        user_payload = json.loads(messages[-1]["content"])

        self.assertEqual(user_payload["director_action"], "slow_emotion")
        self.assertEqual(user_payload["scene_progress"]["location"], "花厅")
        self.assertEqual(user_payload["active_participants"], ["甲", "乙"])
        self.assertIn("不得让离场人物", messages[0]["content"])

    def test_parser_returns_complete_distinct_options(self):
        content = json.dumps(
            {
                "options": [
                    {
                        "title": "误会松动",
                        "focus": "情绪",
                        "beat": "甲看见乙仍收着旧信。",
                        "direction": "让甲从旧信察觉乙仍愿意听解释。",
                        "expected_effect": "制造和解入口。",
                        "risk": "推进过快。",
                    },
                    {
                        "title": "旁人打断",
                        "focus": "冲突",
                        "beat": "门外的脚步声打断争执。",
                        "direction": "用短暂打断让双方从对抗转为共同应对。",
                        "expected_effect": "暂时缓和敌意。",
                        "risk": "可能回避核心误会。",
                    },
                    {
                        "title": "换位观察",
                        "focus": "视角",
                        "beat": "旁白转向乙攥紧信纸的手。",
                        "direction": "从乙的克制动作呈现未说出口的动摇。",
                        "expected_effect": "加深情绪理解。",
                        "risk": "节奏会变慢。",
                    },
                ]
            },
            ensure_ascii=False,
        )

        options = parse_dialogue_director_options(content, expected_count=3)

        self.assertEqual(len(options), 3)
        self.assertEqual(options[0]["title"], "误会松动")
        self.assertEqual(options[2]["focus"], "视角")

    def test_parser_rejects_duplicate_or_incomplete_candidates(self):
        content = json.dumps(
            {
                "options": [
                    {"title": "同一方案", "beat": "甲停下。", "direction": "让甲停下。"},
                    {"title": "同一方案", "beat": "乙停下。", "direction": "让乙停下。"},
                    {"title": "缺少方向", "beat": "门开了。"},
                ]
            },
            ensure_ascii=False,
        )

        with self.assertRaises(ValueError):
            parse_dialogue_director_options(content, expected_count=2)


if __name__ == "__main__":
    unittest.main()
