from __future__ import annotations

import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from src.core.config import Config


class ConfigRuntimeRootTests(unittest.TestCase):
    def test_runtime_root_environment_uses_writable_data_root(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            runtime_root = Path(tmp) / "android-runtime"
            with (
                patch.dict(os.environ, {"ZAOMENG_RUNTIME_ROOT": str(runtime_root)}),
                patch.object(Config, "_find_config", return_value=None),
            ):
                config = Config()

            self.assertEqual(config.project_root, runtime_root.resolve())
            self.assertTrue(Path(config.get_path("characters")).is_dir())
            self.assertTrue(Path(config.get_path("logs")).is_dir())

    def test_explicit_config_path_takes_precedence_over_runtime_root(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            config_path = base / "config.yaml"
            config_path.write_text("{}\n", encoding="utf-8")
            with patch.dict(os.environ, {"ZAOMENG_RUNTIME_ROOT": str(base / "other")}):
                config = Config(str(config_path))

            self.assertEqual(config.project_root, base.resolve())


if __name__ == "__main__":
    unittest.main()
