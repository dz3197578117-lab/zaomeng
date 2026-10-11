#!/usr/bin/env python3
from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from src.plugin_system import PluginError, PluginManifest
from src.plugin_system.packages import inspect_plugin_archive


def package_plugin(source: Path, output: Path | None = None) -> Path:
    root = source.resolve()
    if not root.is_dir():
        raise PluginError(f"插件目录不存在：{root}")
    manifest = PluginManifest.load(root / "plugin.json")
    destination = (
        output.resolve()
        if output is not None
        else (REPO_ROOT / "dist" / f"{manifest.id}-{manifest.version}.zip").resolve()
    )
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(f".{destination.name}.tmp")
    temporary.unlink(missing_ok=True)
    files: list[Path] = []
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise PluginError(f"插件包不能包含符号链接：{path.relative_to(root)}")
        if path.is_dir():
            continue
        relative = path.relative_to(root)
        if "__pycache__" in relative.parts or path.suffix.lower() in {".pyc", ".pyo"}:
            continue
        if path.resolve() in {destination, temporary}:
            continue
        files.append(path)
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            package_root = root.name
            for path in files:
                archive.write(path, f"{package_root}/{path.relative_to(root).as_posix()}")
        inspected = inspect_plugin_archive(temporary)
        if not inspected.compatible:
            raise PluginError(
                f"插件需要 API {inspected.manifest.api_version}，当前打包器仅支持 API 1。"
            )
        temporary.replace(destination)
    except Exception:
        temporary.unlink(missing_ok=True)
        raise
    return destination


def main() -> int:
    parser = argparse.ArgumentParser(description="校验并打包造梦 API v1 插件。")
    parser.add_argument("source", type=Path, help="包含 plugin.json 的插件目录")
    parser.add_argument("--output", "-o", type=Path, help="输出 ZIP 路径")
    args = parser.parse_args()
    try:
        packaged = package_plugin(args.source, args.output)
    except (PluginError, OSError, zipfile.BadZipFile) as exc:
        parser.exit(1, f"打包失败：{exc}\n")
    print(packaged)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
