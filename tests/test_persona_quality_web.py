from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from src.web.artifacts import materialize_profile_source, write_persona_profile
from src.web.review.profile_evidence import (
    PERSONA_EVIDENCE_FILENAME,
    build_profile_evidence_bundle,
)
from src.web.workflow import WebRunService

try:
    from fastapi.testclient import TestClient
    from src.web.app import create_app
except Exception:  # pragma: no cover - optional test dependency guard
    TestClient = None
    create_app = None


FIXTURE_ROOT = Path(__file__).parent / "fixtures" / "persona_quality"


def _build_service_with_persona(root: str) -> tuple[WebRunService, str, Path]:
    service = WebRunService(root)
    run_id = "quality-run"
    run_dir = service.runs_root / run_id
    persona_dir = run_dir / "artifacts" / "characters" / "hongloumeng" / "林黛玉"
    persona_dir.mkdir(parents=True)
    profile = json.loads((FIXTURE_ROOT / "ready_profile.json").read_text(encoding="utf-8"))
    write_persona_profile(persona_dir, profile)
    evidence_bundle = build_profile_evidence_bundle(
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
    service._write_json(persona_dir / PERSONA_EVIDENCE_FILENAME, evidence_bundle)
    service._write_json(
        run_dir / "run_manifest.json",
        {
            "run_id": run_id,
            "status": "ready",
            "webui": {
                "run_dir": str(run_dir),
                "artifact_dir": str(run_dir / "artifacts"),
                "workspace": {"characters_root": str(persona_dir.parent)},
            },
            "artifacts": {"character_dirs": {"林黛玉": str(persona_dir)}},
            "artifact_index": {"characters": []},
            "events": [],
        },
    )
    return service, run_id, persona_dir


class PersonaQualityWebServiceTests(unittest.TestCase):
    def test_report_is_stable_and_written_beside_persona_assets(self):
        with tempfile.TemporaryDirectory() as tmp:
            service, run_id, persona_dir = _build_service_with_persona(tmp)

            first = service.get_persona_quality_report(run_id, "林黛玉")
            report_path = persona_dir / "QUALITY_REPORT.json"
            first_bytes = report_path.read_bytes()
            second = service.get_persona_quality_report(run_id, "林黛玉")

            self.assertEqual(first, second)
            self.assertEqual(first_bytes, report_path.read_bytes())
            self.assertEqual(json.loads(report_path.read_text(encoding="utf-8")), first)
            self.assertEqual(first["schema_version"], "persona-quality-report/v1")
            self.assertEqual(first["metrics"]["evaluated_field_count"], 38)
            self.assertEqual(first["evidence"]["reference_count"], 2)
            self.assertEqual(first["evidence"]["references"][0]["quote"], "林黛玉垂眼道：“你也不用哄我。”")
            self.assertEqual(first["artifact"]["relative_path"], "artifacts/characters/hongloumeng/林黛玉/QUALITY_REPORT.json")
            self.assertEqual(
                first["artifact"]["file_url"],
                f"/api/web/runs/{run_id}/files/artifacts/characters/hongloumeng/林黛玉/QUALITY_REPORT.json",
            )
            self.assertEqual(
                first["evidence_artifact"]["relative_path"],
                "artifacts/characters/hongloumeng/林黛玉/EVIDENCE.generated.json",
            )

    def test_materialization_copies_generated_evidence_into_persona_bundle(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source_path = root / "host" / "PROFILE.generated.md"
            source_path.parent.mkdir(parents=True)
            source_path.write_text("# PROFILE\n- name: 林黛玉\n- novel_id: hongloumeng\n", encoding="utf-8")
            evidence = {"schema_version": "persona-evidence/v1", "references": [{"quote": "原文证据"}]}
            (source_path.parent / PERSONA_EVIDENCE_FILENAME).write_text(
                json.dumps(evidence, ensure_ascii=False),
                encoding="utf-8",
            )

            materialize_profile_source(source_path, root / "persona")

            copied = json.loads((root / "persona" / PERSONA_EVIDENCE_FILENAME).read_text(encoding="utf-8"))
            self.assertEqual(copied, evidence)

    def test_report_changes_after_persona_review_save(self):
        with tempfile.TemporaryDirectory() as tmp:
            service, run_id, _ = _build_service_with_persona(tmp)
            first = service.get_persona_quality_report(run_id, "林黛玉")

            saved = service.save_persona_review(
                run_id,
                "林黛玉",
                {"speech_style": "证据不足", "soul_goal": ""},
            )
            second = service.get_persona_quality_report(run_id, "林黛玉")

            self.assertEqual(saved["fields"]["speech_style"], "")
            self.assertNotEqual(first["input_fingerprint"], second["input_fingerprint"])
            self.assertLess(second["score"], first["score"])
            self.assertIn("field.speech_style.missing", {item["code"] for item in second["issues"]})
            self.assertIn("field.soul_goal.missing", {item["code"] for item in second["issues"]})

    def test_missing_character_raises_file_not_found(self):
        with tempfile.TemporaryDirectory() as tmp:
            service, run_id, _ = _build_service_with_persona(tmp)
            with self.assertRaises(FileNotFoundError):
                service.get_persona_quality_report(run_id, "贾宝玉")


@unittest.skipIf(TestClient is None or create_app is None, "fastapi test dependencies unavailable")
class PersonaQualityWebRouteTests(unittest.TestCase):
    def test_quality_report_route_returns_report_and_404(self):
        with tempfile.TemporaryDirectory() as tmp:
            service, run_id, _ = _build_service_with_persona(tmp)
            client = TestClient(create_app(service))

            response = client.get(f"/api/web/runs/{run_id}/personas/林黛玉/quality-report")
            missing = client.get(f"/api/web/runs/{run_id}/personas/贾宝玉/quality-report")

            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()["schema_version"], "persona-quality-report/v1")
            self.assertEqual(response.json()["character"], "林黛玉")
            self.assertEqual(missing.status_code, 404)
            self.assertEqual(missing.json()["detail"], "Character not found.")


if __name__ == "__main__":
    unittest.main()
