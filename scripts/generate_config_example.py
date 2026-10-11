#!/usr/bin/env python3
"""Generate or verify ``config.yaml.example`` from ``Config.DEFAULT_CONFIG``."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import yaml


PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUTPUT_PATH = PROJECT_ROOT / "config.yaml.example"
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from src.core.config import Config  # noqa: E402


GENERATED_HEADER = (
    "# Generated from src/core/config.py (Config.DEFAULT_CONFIG).\n"
    "# Run `python scripts/generate_config_example.py` to refresh this file.\n\n"
)


def render_config_example() -> str:
    """Return the deterministic YAML representation of the default config."""

    payload = yaml.safe_dump(
        Config.DEFAULT_CONFIG,
        allow_unicode=True,
        default_flow_style=False,
        sort_keys=False,
        width=1000,
    )
    return GENERATED_HEADER + payload


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="Exit unsuccessfully when the generated output differs from the existing file.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT_PATH,
        help="Output path (defaults to the repository config.yaml.example).",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    output_path = args.output.resolve()
    rendered = render_config_example()

    if args.check:
        current = output_path.read_text(encoding="utf-8") if output_path.exists() else ""
        if current != rendered:
            print(
                f"{output_path} is out of sync with Config.DEFAULT_CONFIG. "
                "Run `python scripts/generate_config_example.py`.",
                file=sys.stderr,
            )
            return 1
        print(f"{output_path} is in sync with Config.DEFAULT_CONFIG.")
        return 0

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(rendered, encoding="utf-8", newline="\n")
    print(f"Wrote {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
