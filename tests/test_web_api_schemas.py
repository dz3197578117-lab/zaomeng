from __future__ import annotations

import unittest

from pydantic import ValidationError

from src.web.api.compat import model_to_dict
from src.web.api.schemas import (
    CreateRunRequest,
    CreateCrossoverSpaceRequest,
    DeleteSessionsRequest,
    DialogueAssociationsRequest,
    DialogueResponseItem,
    IngestDialogueTurnRequest,
    SavePersonaReviewRequest,
    SaveSelfCardRequest,
)


class WebApiSchemasTests(unittest.TestCase):
    def test_crossover_request_requires_two_to_eight_participants(self):
        participant = {"run_id": "run-1", "character": "甲"}
        with self.assertRaises(ValidationError):
            CreateCrossoverSpaceRequest(title="测试", participants=[participant])
        accepted = CreateCrossoverSpaceRequest(
            title="测试",
            participants=[participant, {"run_id": "run-2", "character": "乙"}],
        )
        self.assertEqual(len(accepted.participants), 2)
        accepted_maximum = CreateCrossoverSpaceRequest(
            title="测试",
            participants=[
                {"run_id": f"run-{index}", "character": f"人物{index}"}
                for index in range(8)
            ],
        )
        self.assertEqual(len(accepted_maximum.participants), 8)
        with self.assertRaises(ValidationError):
            CreateCrossoverSpaceRequest(title="测试", participants=[participant] * 9)

    def test_create_run_request_requires_non_empty_characters(self):
        with self.assertRaises(ValidationError):
            CreateRunRequest(
                novel_name="demo.txt",
                novel_content_base64="ZGVtbw==",
                characters=[],
            )

    def test_ingest_dialogue_turn_request_requires_non_empty_responses(self):
        with self.assertRaises(ValidationError):
            IngestDialogueTurnRequest(responses=[])

    def test_ingest_dialogue_turn_request_accepts_responses(self):
        payload = IngestDialogueTurnRequest(
            responses=[DialogueResponseItem(speaker="林黛玉", message="你来了。")]
        )

        self.assertEqual(len(payload.responses), 1)

    def test_delete_sessions_request_requires_items(self) -> None:
        with self.assertRaises(ValidationError):
            DeleteSessionsRequest(items=[])

    def test_dialogue_association_request_limits_option_count(self):
        self.assertEqual(DialogueAssociationsRequest().option_count, 3)
        with self.assertRaises(ValidationError):
            DialogueAssociationsRequest(option_count=1)
        with self.assertRaises(ValidationError):
            DialogueAssociationsRequest(option_count=5)

    def test_all_persona_review_fields_can_be_saved(self):
        payload = model_to_dict(
            SavePersonaReviewRequest(
                gender="女性",
                age_stage="青年",
                appearance_feature="眉眼清秀",
                habit_action="常先垂眼再开口",
                preference_like="诗词",
                dislike_hate="虚伪",
            )
        )

        self.assertEqual(payload["gender"], "女性")
        self.assertEqual(payload["age_stage"], "青年")
        self.assertEqual(payload["appearance_feature"], "眉眼清秀")
        self.assertEqual(payload["habit_action"], "常先垂眼再开口")
        self.assertEqual(payload["preference_like"], "诗词")
        self.assertEqual(payload["dislike_hate"], "虚伪")

    def test_all_self_card_profile_fields_can_be_saved(self):
        payload = model_to_dict(
            SaveSelfCardRequest(
                gender="女性",
                age_stage="青年",
                appearance_feature="眉眼清秀",
                habit_action="常先垂眼再开口",
                preference_like="诗词",
                dislike_hate="虚伪",
            )
        )

        self.assertEqual(payload["gender"], "女性")
        self.assertEqual(payload["age_stage"], "青年")
        self.assertEqual(payload["appearance_feature"], "眉眼清秀")
        self.assertEqual(payload["habit_action"], "常先垂眼再开口")
        self.assertEqual(payload["preference_like"], "诗词")
        self.assertEqual(payload["dislike_hate"], "虚伪")


if __name__ == "__main__":
    unittest.main()
