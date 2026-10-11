from __future__ import annotations

import unittest

from src.modules.distillation_persona import render_profile_md
from src.utils.persona_placeholders import is_empty_persona_marker, sanitize_persona_value
from src.web.review.profile_repair import (
    apply_profile_missing_fallbacks,
    merge_profile_patch,
    sanitize_profile_identity_fields,
    sanitize_profile_surface_fields,
)


class PersonaPlaceholderTests(unittest.TestCase):
    def test_recognizes_unknown_markers_and_ellipsis_only(self) -> None:
        for value in ("证据不足", "信息不足", "未知", "不详", "...", "…", "……", " ⋯⋯ "):
            with self.subTest(value=value):
                self.assertTrue(is_empty_persona_marker(value))
        self.assertFalse(is_empty_persona_marker("说到一半……又停住"))

    def test_recursive_sanitizer_removes_markers_from_lists_and_scalars(self) -> None:
        cleaned = sanitize_persona_value(
            {
                "speech_style": "证据不足",
                "typical_lines": ["……", "真正的台词"],
                "speech_habits": {"cadence": "..."},
            }
        )
        self.assertEqual(cleaned["speech_style"], "")
        self.assertEqual(cleaned["typical_lines"], ["真正的台词"])
        self.assertEqual(cleaned["speech_habits"]["cadence"], "")

    def test_profile_renderer_writes_blank_fields_instead_of_placeholders(self) -> None:
        rendered = render_profile_md(
            {
                "name": "甲",
                "speech_style": "证据不足",
                "typical_lines": ["...", "有效台词"],
                "gender": "……",
            }
        )
        self.assertIn("- speech_style: \n", rendered)
        self.assertIn("- gender: \n", rendered)
        self.assertIn("- typical_lines: 有效台词\n", rendered)
        self.assertNotIn("证据不足", rendered)
        self.assertNotIn("...", rendered)

    def test_repair_patch_and_fallback_keep_unknown_values_empty(self) -> None:
        profile: dict[str, object] = {}
        merge_profile_patch(
            profile,
            "- speech_style: 证据不足\n- typical_lines: ……",
            profile_list_fields={"typical_lines"},
            profile_map_fields=set(),
        )
        apply_profile_missing_fallbacks(
            profile,
            completion_fields=("speech_style", "typical_lines"),
            profile_list_fields={"typical_lines"},
            profile_map_fields=set(),
        )
        self.assertEqual(profile["speech_style"], "")
        self.assertEqual(profile["typical_lines"], [])

    def test_field_sanitizers_clear_placeholder_inputs(self) -> None:
        profile = {
            "gender": "证据不足",
            "age_stage": "……",
            "appearance_feature": "未知",
            "habit_action": "...",
        }
        sanitize_profile_identity_fields(profile)
        sanitize_profile_surface_fields(profile)
        self.assertEqual(profile, {key: "" for key in profile})


if __name__ == "__main__":
    unittest.main()
