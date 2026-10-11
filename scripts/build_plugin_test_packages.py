#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import zipfile
from pathlib import Path


PLUGIN_SOURCE = """class FixturePlugin:
    def activate(self, host):
        self.host = host

    def deactivate(self):
        self.host = None

    def execute_chat_action(self, action_id, request):
        return {"suggestion": "fixture-ok"}

def create_plugin():
    return FixturePlugin()
"""


def _manifest(
    *, plugin_id: str = "com.example.zaomeng-fixture", version: str = "1.0.0", api_version: str = "1"
) -> dict[str, object]:
    return {
        "id": plugin_id,
        "name": "插件验收样例",
        "version": version,
        "apiVersion": api_version,
        "entry": "main.py",
        "description": "用于验证造梦插件安装、更新和安全拦截。",
        "defaultEnabled": False,
        "permissions": ["chat.context.read", "chat.draft.write"],
        "contributes": {
            "chatActions": [
                {"id": "fixture", "title": "验收样例", "placement": "composer"}
            ]
        },
    }


def _write_package(
    path: Path,
    *,
    plugin_id: str = "com.example.zaomeng-fixture",
    version: str = "1.0.0",
    api_version: str = "1",
    traversal: bool = False,
) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(
            "plugin-fixture/plugin.json",
            json.dumps(
                _manifest(plugin_id=plugin_id, version=version, api_version=api_version),
                ensure_ascii=False,
                indent=2,
            ),
        )
        archive.writestr("plugin-fixture/main.py", PLUGIN_SOURCE)
        if traversal:
            archive.writestr("../escape.txt", "must never be extracted")


def build_fixtures(output: Path) -> list[Path]:
    output.mkdir(parents=True, exist_ok=True)
    packages = [
        output / "01-valid-v1.zip",
        output / "02-update-v2.zip",
        output / "03-incompatible-api-v2.zip",
        output / "04-path-traversal.zip",
        output / "05-official-id-collision.zip",
        output / "06-corrupt.zip",
    ]
    _write_package(packages[0])
    _write_package(packages[1], version="2.0.0")
    _write_package(packages[2], api_version="2")
    _write_package(packages[3], traversal=True)
    _write_package(packages[4], plugin_id="com.zaomeng.ai-association")
    packages[5].write_bytes(b"not-a-zip")
    return packages


def main() -> int:
    parser = argparse.ArgumentParser(description="生成造梦插件安装验收包。")
    parser.add_argument(
        "--output",
        "-o",
        type=Path,
        default=Path("dist/plugin-test-packages"),
        help="输出目录",
    )
    args = parser.parse_args()
    for package in build_fixtures(args.output.resolve()):
        print(package)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
