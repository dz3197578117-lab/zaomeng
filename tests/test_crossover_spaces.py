from __future__ import annotations

import base64
import os
import tempfile
import unittest
from pathlib import Path

from src.web.workflow import WebRunService


class CrossoverSpaceTests(unittest.TestCase):
    def _source_run(self, service: WebRunService, title: str, character: str) -> dict:
        run = service.create_run(
            novel_name=f"{title}.txt",
            novel_content_base64=base64.b64encode(f"{character}登场。".encode()).decode(),
            characters=[character],
            defer_run=True,
        )
        profile = f"- name: {character}\n- novel_id: {title}\n- core_identity: {title}人物\n"
        service.ingest_character_result(
            run["run_id"],
            character=character,
            content_base64=base64.b64encode(profile.encode()).decode(),
        )
        return service.get_run(run["run_id"])

    def test_crossover_copies_snapshots_without_mutating_sources(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            first = self._source_run(service, "甲书", "甲")
            second = self._source_run(service, "乙书", "乙")
            source_files = [
                Path(run["artifact_index"]["characters"][0]["profile_file"])
                for run in (first, second)
            ]
            source_bytes = [path.read_bytes() for path in source_files]

            crossover = service.create_crossover_space(
                title="相逢测试",
                world_setting="一座与原作无关的客栈。",
                participants=[
                    {"run_id": first["run_id"], "character": "甲"},
                    {"run_id": second["run_id"], "character": "乙"},
                ],
            )

            self.assertEqual(crossover["entrypoint"], "crossover_beta")
            self.assertTrue(crossover["beta_feature"]["unstable"])
            self.assertEqual(
                {item["name"] for item in crossover["artifact_index"]["characters"]},
                {"甲", "乙"},
            )
            self.assertEqual([path.read_bytes() for path in source_files], source_bytes)
            target_root = Path(crossover["webui"]["workspace"]["characters_root"])
            self.assertNotEqual(target_root, source_files[0].parent.parent)
            self.assertTrue((target_root / "甲").exists())
            self.assertTrue((target_root / "乙").exists())

            session = service.dialogue.create_session(
                service._require_manifest(crossover["run_id"]),
                mode="observe",
                participants=["甲", "乙"],
            )
            self.assertTrue(
                (service.runs_root / crossover["run_id"] / "dialogue" / session["session_id"] / "session.json").exists()
            )
            for source in (first, second):
                self.assertFalse((service.runs_root / source["run_id"] / "dialogue").exists())

    @unittest.skipIf(os.name == "nt", "Windows does not enforce POSIX read-only directory modes")
    def test_crossover_does_not_copy_read_only_source_permissions(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            first = self._source_run(service, "甲书", "甲")
            second = self._source_run(service, "乙书", "乙")
            source_dirs = [
                Path(run["artifact_index"]["characters"][0]["profile_file"]).parent
                for run in (first, second)
            ]
            for source_dir in source_dirs:
                for path in source_dir.rglob("*"):
                    path.chmod(0o500 if path.is_dir() else 0o400)
                source_dir.chmod(0o500)

            try:
                crossover = service.create_crossover_space(
                    title="只读来源测试",
                    world_setting="独立世界。",
                    participants=[
                        {"run_id": first["run_id"], "character": "甲"},
                        {"run_id": second["run_id"], "character": "乙"},
                    ],
                )
                target_root = Path(crossover["webui"]["workspace"]["characters_root"])
                target_file = target_root / "甲" / Path(first["artifact_index"]["characters"][0]["profile_file"]).name
                target_file.write_text(target_file.read_text(encoding="utf-8") + "\n可写", encoding="utf-8")
            finally:
                for source_dir in source_dirs:
                    source_dir.chmod(0o700)
                    for path in source_dir.rglob("*"):
                        path.chmod(0o700 if path.is_dir() else 0o600)


if __name__ == "__main__":
    unittest.main()
