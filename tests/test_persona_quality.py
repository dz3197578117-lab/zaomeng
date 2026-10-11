from __future__ import annotations

import json
import unittest
from pathlib import Path

from src.web.review.profile_evidence import build_profile_evidence_bundle
from src.web.review.persona_quality import evaluate_persona_quality


FIXTURE_ROOT = Path(__file__).resolve().parent / "fixtures" / "persona_quality"


def load_fixture(name: str) -> dict:
    return json.loads((FIXTURE_ROOT / name).read_text(encoding="utf-8"))


class PersonaQualityTests(unittest.TestCase):
    def test_ready_profile_produces_stable_machine_readable_report(self):
        profile = load_fixture("ready_profile.json")

        first = evaluate_persona_quality(profile)
        second = evaluate_persona_quality(profile)

        self.assertEqual(first, second)
        self.assertEqual(first["schema_version"], "persona-quality-report/v1")
        self.assertEqual(first["max_score"], 100)
        self.assertEqual(sum(item["max_score"] for item in first["dimensions"]), 100)
        self.assertEqual(first["grade"], "ready")
        self.assertGreaterEqual(first["score"], 80)
        self.assertTrue(first["input_fingerprint"].startswith("sha256:"))
        self.assertEqual(first, load_fixture("ready_report.json"))
        json.dumps(first, ensure_ascii=False, sort_keys=True)

    def test_sparse_profile_reports_actionable_high_priority_gaps(self):
        report = evaluate_persona_quality(load_fixture("sparse_profile.json"))

        self.assertEqual(report["grade"], "insufficient")
        self.assertLess(report["score"], 40)
        self.assertGreater(report["metrics"]["missing_field_count"], 0)
        issue_codes = {item["code"] for item in report["issues"]}
        self.assertIn("field.speech_style.insufficient", issue_codes)
        self.assertIn("field.soul_goal.missing", issue_codes)
        self.assertIn("evidence.none", issue_codes)

    def test_fingerprint_changes_when_profile_content_changes(self):
        profile = load_fixture("ready_profile.json")
        initial = evaluate_persona_quality(profile)
        profile["speech_style"] = "短促直接，不使用反话"

        changed = evaluate_persona_quality(profile)

        self.assertNotEqual(initial["input_fingerprint"], changed["input_fingerprint"])

    def test_field_results_and_issues_include_dimension_matched_source_evidence(self):
        bundle = build_profile_evidence_bundle(
            {
                "request": {
                    "excerpt": "林黛玉垂眼道：“你也不用哄我。”\n林黛玉心想，真心若被辜负，热闹也无用。",
                    "excerpt_stages": {
                        "start": "林黛玉垂眼道：“你也不用哄我。”",
                        "mid": "林黛玉心想，真心若被辜负，热闹也无用。",
                        "end": "",
                    },
                }
            },
            character="林黛玉",
            chunk_count=2,
        )

        report = evaluate_persona_quality(load_fixture("sparse_profile.json"), evidence_bundle=bundle)

        self.assertEqual(report["evidence"]["reference_count"], 2)
        speech_result = next(item for item in report["field_results"] if item["field"] == "speech_style")
        self.assertEqual(speech_result["evidence"][0]["kind"], "dialogue")
        self.assertEqual(speech_result["evidence"][0]["match_basis"], "dimension:voice")
        soul_issue = next(item for item in report["issues"] if item["code"] == "field.soul_goal.missing")
        self.assertEqual(soul_issue["evidence"][0]["kind"], "thought")
        self.assertEqual(soul_issue["evidence"][0]["quote"], "林黛玉心想，真心若被辜负，热闹也无用。")


if __name__ == "__main__":
    unittest.main()
