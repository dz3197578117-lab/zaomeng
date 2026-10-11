from __future__ import annotations

import unittest

from src.web.chat.chapter_outline import build_chapter_outline


class ChapterOutlineTests(unittest.TestCase):
    def test_groups_events_by_scene_and_extracts_summary_cast_and_hooks(self):
        outline = build_chapter_outline(
            [
                {
                    "scene_card_id": "scene-1",
                    "title": "Front Hall",
                    "location": "Manor",
                    "ts": "2026-01-01T10:00:00Z",
                },
                {
                    "scene_card_id": "scene-2",
                    "title": "Garden",
                    "location": "Garden",
                    "scene_card": {"time_hint": "Night"},
                    "ts": "2026-01-01T11:00:00Z",
                },
            ],
            [
                {
                    "turn_id": "turn-1",
                    "title": "A visitor arrives",
                    "participants": ["A", "B"],
                    "updated_at": "2026-01-01T10:30:00Z",
                    "responses": [{"speaker": "A", "message": "Welcome."}],
                },
                {
                    "turn_id": "turn-2",
                    "title": "A promise under the tree",
                    "participants": ["A"],
                    "updated_at": "2026-01-01T11:30:00Z",
                    "responses": [
                        {"speaker": "A", "message": "我答应明天告诉你真相。"}
                    ],
                },
            ],
            session_summary={"unresolved_threads": "还有一封信尚未拆开。"},
        )

        self.assertEqual(outline["chapter_count"], 2)
        self.assertEqual(outline["event_count"], 2)
        first, second = outline["chapters"]
        self.assertEqual(first["event_count"], 1)
        self.assertEqual(first["participants"], ["A", "B"])
        self.assertIn("A visitor arrives", first["summary"])
        self.assertEqual(second["time_hint"], "Night")
        self.assertEqual(second["end_turn_id"], "turn-2")
        self.assertTrue(second["is_current"])
        self.assertIn("答应明天", second["hooks"][0])

    def test_builds_implicit_scene_for_legacy_session_without_scene_history(self):
        outline = build_chapter_outline(
            [],
            [
                {
                    "turn_id": "turn-old",
                    "title": "Legacy event",
                    "location": "Station",
                    "updated_at": "",
                }
            ],
        )

        chapter = outline["chapters"][0]
        self.assertEqual(outline["chapter_count"], 1)
        self.assertEqual(chapter["title"], "未命名场景")
        self.assertEqual(chapter["location"], "Station")
        self.assertEqual(chapter["start_turn_id"], "turn-old")


if __name__ == "__main__":
    unittest.main()
