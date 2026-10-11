from __future__ import annotations

import base64
import json
import tempfile
import unittest
from pathlib import Path

from src.web.workflow import WebRunService


class CompletePackageBackupTests(unittest.TestCase):
    def test_regular_package_restores_chapter_and_dialogue_data(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            run = service.create_run(
                novel_name="backup.txt",
                novel_content_base64=base64.b64encode("第一章。".encode("utf-8")).decode("ascii"),
                characters=["小甲"],
                defer_run=True,
            )
            run_dir = Path(run["webui"]["run_dir"])
            session_dir = run_dir / "dialogue" / "session-1"
            session_dir.mkdir(parents=True)
            (session_dir / "session.json").write_text(
                json.dumps(
                    {
                        "run_id": run["run_id"],
                        "session_id": "session-1",
                        "participants": ["小甲"],
                        "transcript": [{"speaker": "小甲", "message": "旧会话内容。"}],
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            service.save_chapter(run["run_id"], title="第一章", content="章节草稿")

            exported = service.export_run_package(run["run_id"])
            imported = service.import_run_package(
                filename=exported["filename"],
                content_base64=base64.b64encode(Path(exported["path"]).read_bytes()).decode("ascii"),
            )
            imported_dir = Path(imported["webui"]["run_dir"])
            restored_session = json.loads((imported_dir / "dialogue" / "session-1" / "session.json").read_text(encoding="utf-8"))

            self.assertEqual(restored_session["run_id"], imported["run_id"])
            self.assertEqual(restored_session["transcript"][0]["message"], "旧会话内容。")
            self.assertEqual(service.list_chapters(imported["run_id"])[0]["content"], "章节草稿")

    def test_builtin_package_keeps_user_dialogues_out(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            run = service.create_run(
                novel_name="builtin.txt",
                novel_content_base64=base64.b64encode("第一章。".encode("utf-8")).decode("ascii"),
                characters=["小甲"],
                defer_run=True,
            )
            run_dir = Path(run["webui"]["run_dir"])
            (run_dir / "dialogue" / "session-1").mkdir(parents=True)
            exported = service.export_run_package(run["run_id"], builtin=True)

            import zipfile

            with zipfile.ZipFile(exported["path"]) as archive:
                self.assertFalse(any(name.startswith("run/dialogue/") for name in archive.namelist()))


if __name__ == "__main__":
    unittest.main()
