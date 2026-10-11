from __future__ import annotations

import unittest

from src.web.chat.character_arc import build_character_arcs


def record(turn_id: str, updated_at: str, snapshot: dict[str, str], message: str):
    return {
        "turn_id": turn_id,
        "updated_at": updated_at,
        "payload": {"input": {"message": "continue"}},
        "result": {"responses": [{"speaker": "A", "message": message}]},
        "checkpoint": {"character_snapshots": {"A": snapshot}},
    }


class CharacterArcTests(unittest.TestCase):
    def test_records_only_meaningful_state_changes_and_explains_them(self):
        arcs = build_character_arcs(
            ["A", "B"],
            [
                record(
                    "turn-1",
                    "2026-01-01T10:00:00Z",
                    {
                        "mood": "guarded",
                        "interaction_state": "testing",
                        "focus": "find the truth",
                        "last_event": "A refuses to trust the explanation.",
                    },
                    "I still doubt you.",
                ),
                record(
                    "turn-2",
                    "2026-01-01T10:10:00Z",
                    {
                        "mood": "guarded",
                        "interaction_state": "testing",
                        "focus": "find the truth",
                        "last_event": "The discussion continues.",
                    },
                    "Go on.",
                ),
                record(
                    "turn-3",
                    "2026-01-01T10:20:00Z",
                    {
                        "mood": "relieved",
                        "interaction_state": "cooperating",
                        "focus": "repair the relationship",
                        "last_event": "A accepts the evidence and offers peace.",
                    },
                    "I believe you now.",
                ),
            ],
        )

        arc = arcs[0]
        self.assertEqual(arc["name"], "A")
        self.assertEqual(len(arc["points"]), 2)
        self.assertEqual(arc["current"]["mood"], "relieved")
        self.assertEqual(
            {item["field"] for item in arc["points"][-1]["changes"]},
            {"mood", "interaction_state", "focus"},
        )
        self.assertIn("accepts the evidence", arc["points"][-1]["reason"])
        self.assertIn("情绪", arc["growth_summary"])
        self.assertEqual(arcs[1]["points"], [])

    def test_continues_from_inherited_branch_state(self):
        inherited = [
            {
                "name": "A",
                "points": [
                    {
                        "turn_id": "turn-parent",
                        "turn_number": 1,
                        "state": {"mood": "guarded", "focus": "escape"},
                        "changes": [],
                        "reason": "Parent branch state",
                        "inherited": True,
                    }
                ],
            }
        ]
        arcs = build_character_arcs(
            ["A"],
            [
                record(
                    "turn-child",
                    "2026-01-01T11:00:00Z",
                    {"mood": "determined", "focus": "confront the danger"},
                    "We face it together.",
                )
            ],
            inherited_arcs=inherited,
        )

        self.assertEqual(len(arcs[0]["points"]), 2)
        self.assertTrue(arcs[0]["points"][0]["inherited"])
        self.assertEqual(arcs[0]["points"][1]["turn_number"], 2)


if __name__ == "__main__":
    unittest.main()
