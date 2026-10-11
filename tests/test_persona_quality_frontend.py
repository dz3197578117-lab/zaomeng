from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class PersonaQualityFrontendContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.api_source = (ROOT / "src/web/static/js/webui-api.js").read_text(encoding="utf-8")
        cls.island_source = (ROOT / "src/web/static/js/persona-review-vue-island.js").read_text(encoding="utf-8")
        cls.style_source = (ROOT / "src/web/static/styles/modal.css").read_text(encoding="utf-8")

    def test_api_encodes_character_and_exports_quality_report_method(self):
        self.assertIn("async function getPersonaQualityReport(runId, character)", self.api_source)
        self.assertIn("/personas/${encodeURIComponent(character)}/quality-report", self.api_source)
        self.assertRegex(
            self.api_source,
            r"window\.__ZAOMENG_WEBUI_API__\s*=\s*\{[\s\S]*?getPersonaQualityReport,",
        )

    def test_persona_review_loads_report_and_refreshes_it_after_save(self):
        self.assertIn("const qualityLoaded = await loadQualityReport(runId, character);", self.island_source)
        save_call = "const saved = await webuiApi.savePersonaReview(runId, character, clone(state.fields));"
        refresh_call = "await loadQualityReport(runId, character);"
        save_offset = self.island_source.index(save_call)
        refresh_offset = self.island_source.index(refresh_call, save_offset)
        self.assertGreater(refresh_offset, save_offset)
        self.assertIn("人物档案已载入，质量报告暂时不可用。", self.island_source)

    def test_non_ready_fields_reuse_schema_card_feedback(self):
        self.assertIn('if (!field || result?.status === "ready") return;', self.island_source)
        self.assertIn('kind: "error"', self.island_source)
        self.assertIn('message: issue?.suggestion', self.island_source)
        self.assertIn(':feedback="state.feedback[item.field] || null"', self.island_source)

    def test_quality_panel_exposes_score_dimensions_evidence_issues_and_download(self):
        for contract in (
            'aria-label="人物质量报告"',
            "state.qualityReport.score",
            "state.qualityReport.dimensions",
            "state.qualityReport.evidence.dialogue_count",
            "visibleEvidenceReferences",
            "查看原文证据",
            "查看相关原文",
            "visibleQualityIssues",
            "state.qualityReport.artifact.file_url",
        ):
            self.assertIn(contract, self.island_source)
        self.assertIn(".persona-quality-panel", self.style_source)
        self.assertIn(".persona-quality-evidence-library", self.style_source)
        self.assertIn(".persona-quality-issue-evidence", self.style_source)
        self.assertIn("@media (max-width: 720px)", self.style_source)


if __name__ == "__main__":
    unittest.main()
