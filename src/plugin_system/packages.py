from __future__ import annotations

import json
import shutil
import stat
import tempfile
import zipfile
from dataclasses import dataclass, replace
from pathlib import Path, PurePosixPath
from typing import Any

from .runtime import PLUGIN_API_VERSION, PluginError, PluginManifest


MAX_PACKAGE_BYTES = 10 * 1024 * 1024
MAX_EXTRACTED_BYTES = 30 * 1024 * 1024
MAX_PACKAGE_FILES = 500


@dataclass(frozen=True)
class InspectedPluginPackage:
    manifest: PluginManifest
    root_prefix: str
    file_count: int
    extracted_bytes: int
    compatible: bool = True

    def to_dict(self) -> dict[str, Any]:
        return {
            "manifest": self.manifest.to_dict(),
            "rootPrefix": self.root_prefix,
            "fileCount": self.file_count,
            "extractedBytes": self.extracted_bytes,
            "compatible": self.compatible,
        }


def _safe_member_path(name: str) -> PurePosixPath:
    normalized = str(name or "").replace("\\", "/")
    path = PurePosixPath(normalized)
    if not normalized or path.is_absolute() or ".." in path.parts:
        raise PluginError(f"插件包包含不安全路径：{name!r}。")
    if path.parts and ":" in path.parts[0]:
        raise PluginError(f"插件包包含不安全路径：{name!r}。")
    return path


def _is_symlink(info: zipfile.ZipInfo) -> bool:
    mode = (info.external_attr >> 16) & 0xFFFF
    return stat.S_ISLNK(mode)


def inspect_plugin_archive(package_path: Path) -> InspectedPluginPackage:
    if not package_path.is_file() or package_path.stat().st_size <= 0:
        raise PluginError("插件包为空。")
    if package_path.stat().st_size > MAX_PACKAGE_BYTES:
        raise PluginError("插件包超过 10 MB 限制。")
    try:
        archive = zipfile.ZipFile(package_path)
    except (OSError, zipfile.BadZipFile) as exc:
        raise PluginError("插件包不是有效的 ZIP 文件。") from exc
    with archive:
        files = [info for info in archive.infolist() if not info.is_dir()]
        if not files or len(files) > MAX_PACKAGE_FILES:
            raise PluginError("插件包文件数量无效或超过 500 个。")
        total_size = 0
        manifest_entries: list[tuple[zipfile.ZipInfo, PurePosixPath]] = []
        for info in files:
            path = _safe_member_path(info.filename)
            if _is_symlink(info):
                raise PluginError("插件包不能包含符号链接。")
            total_size += max(0, int(info.file_size))
            if total_size > MAX_EXTRACTED_BYTES:
                raise PluginError("插件解压后超过 30 MB 限制。")
            if path.name == "plugin.json" and len(path.parts) <= 2:
                manifest_entries.append((info, path))
        if len(manifest_entries) != 1:
            raise PluginError("插件包必须且只能包含一个根级 plugin.json。")
        manifest_info, manifest_path = manifest_entries[0]
        root_prefix = "" if len(manifest_path.parts) == 1 else manifest_path.parts[0]
        for info in files:
            path = _safe_member_path(info.filename)
            if root_prefix and (not path.parts or path.parts[0] != root_prefix):
                raise PluginError("插件包根目录外不能包含其他文件。")
        with tempfile.TemporaryDirectory(prefix="zaomeng-plugin-inspect-") as tmp:
            root = Path(tmp)
            extract_plugin_archive(package_path, root, root_prefix=root_prefix)
            manifest_file = root / "plugin.json"
            try:
                raw_manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError) as exc:
                raise PluginError("无法读取 plugin.json。") from exc
            requested_api = str(raw_manifest.get("apiVersion", "")).strip()
            compatible = requested_api == PLUGIN_API_VERSION
            if not compatible:
                validation_manifest = dict(raw_manifest)
                validation_manifest["apiVersion"] = PLUGIN_API_VERSION
                manifest_file.write_text(
                    json.dumps(validation_manifest, ensure_ascii=False),
                    encoding="utf-8",
                )
            manifest = PluginManifest.load(manifest_file)
            if not compatible:
                manifest = replace(manifest, api_version=requested_api)
            entry = (root / manifest.entry).resolve()
            if not entry.is_relative_to(root.resolve()) or not entry.is_file():
                raise PluginError(f"插件入口不存在：{manifest.entry}。")
        return InspectedPluginPackage(
            manifest=manifest,
            root_prefix=root_prefix,
            file_count=len(files),
            extracted_bytes=total_size,
            compatible=compatible,
        )


def extract_plugin_archive(
    package_path: Path, target_root: Path, *, root_prefix: str
) -> None:
    target = target_root.resolve()
    target.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(package_path) as archive:
        for info in archive.infolist():
            if info.is_dir():
                continue
            path = _safe_member_path(info.filename)
            relative_parts = path.parts[1:] if root_prefix else path.parts
            if not relative_parts:
                continue
            destination = target.joinpath(*relative_parts).resolve()
            if not destination.is_relative_to(target):
                raise PluginError("插件包解压路径越界。")
            destination.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(info) as source, destination.open("wb") as output:
                shutil.copyfileobj(source, output)


def write_stage_metadata(path: Path, payload: dict[str, Any]) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def read_stage_metadata(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise PluginError("插件安装会话已失效，请重新选择插件包。") from exc
    if not isinstance(payload, dict):
        raise PluginError("插件安装会话无效。")
    return payload
