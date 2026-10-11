from __future__ import annotations

import unittest

from src.modules.relationships import RelationshipExtractor


class _MentioningDistiller:
    @staticmethod
    def text_mentions_any_alias(text: str, aliases: list[str]) -> bool:
        return any(alias in text for alias in aliases)


class RelationshipExtractionTests(unittest.TestCase):
    def setUp(self) -> None:
        self.extractor = RelationshipExtractor.__new__(RelationshipExtractor)
        self.extractor.distiller = _MentioningDistiller()

    def test_adjacent_sentences_form_a_relation_evidence_window(self) -> None:
        interactions = self.extractor._extract_pair_interactions(
            "甲转身离开。乙追上去叫住了他。",
            ["甲", "乙"],
        )

        pair_key = self.extractor._pair_key("甲", "乙")
        self.assertIn(pair_key, interactions)
        self.assertIn("甲转身离开。 乙追上去叫住了他。", interactions[pair_key])

    def test_distant_sentences_do_not_form_an_interaction(self) -> None:
        interactions = self.extractor._extract_pair_interactions(
            "甲独自出门。路边的雨越下越大。乙在另一处整理信件。",
            ["甲", "乙"],
        )

        self.assertNotIn(self.extractor._pair_key("甲", "乙"), interactions)


if __name__ == "__main__":
    unittest.main()
