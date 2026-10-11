#!/usr/bin/env python3

from __future__ import annotations

import unittest

import yaml

from scripts.generate_config_example import DEFAULT_OUTPUT_PATH, render_config_example
from src.core.config import Config


class ConfigExampleSyncTests(unittest.TestCase):
    def test_example_is_generated_from_default_config(self) -> None:
        example_text = DEFAULT_OUTPUT_PATH.read_text(encoding="utf-8")

        self.assertEqual(example_text, render_config_example())
        self.assertEqual(yaml.safe_load(example_text), Config.DEFAULT_CONFIG)

    def test_provider_api_uses_accurate_name(self) -> None:
        config = object.__new__(Config)

        self.assertEqual(config.get_supported_providers(), list(Config.SUPPORTED_PROVIDERS))
        with self.assertWarnsRegex(DeprecationWarning, "get_supported_providers"):
            self.assertEqual(config.get_supported_models(), list(Config.SUPPORTED_PROVIDERS))

    def test_legacy_mutating_setters_are_removed(self) -> None:
        self.assertFalse(hasattr(Config, "set_api_key"))
        self.assertFalse(hasattr(Config, "set_model"))


if __name__ == "__main__":
    unittest.main()
