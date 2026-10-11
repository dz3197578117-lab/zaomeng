from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from scripts.build_plugin_test_packages import build_fixtures
from src.plugin_system import PluginError
from src.plugin_system.packages import inspect_plugin_archive


class PluginFixtureBuilderTests(unittest.TestCase):
    def test_builds_valid_and_rejection_fixture_packages(self):
        with tempfile.TemporaryDirectory() as tmp:
            packages = build_fixtures(Path(tmp))

            self.assertEqual(len(packages), 6)
            self.assertTrue(all(path.is_file() for path in packages))
            valid = inspect_plugin_archive(packages[0])
            update = inspect_plugin_archive(packages[1])
            incompatible = inspect_plugin_archive(packages[2])
            self.assertEqual(valid.manifest.version, "1.0.0")
            self.assertEqual(update.manifest.version, "2.0.0")
            self.assertFalse(incompatible.compatible)
            with self.assertRaisesRegex(PluginError, "不安全路径"):
                inspect_plugin_archive(packages[3])
            with self.assertRaisesRegex(PluginError, "有效的 ZIP"):
                inspect_plugin_archive(packages[5])


if __name__ == "__main__":
    unittest.main()
