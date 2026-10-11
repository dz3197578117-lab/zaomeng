from __future__ import annotations

import platform
import shutil
import sys
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit, urlunsplit

from src.web.manifest import load_json_file
from src.web.time_utils import utc_now


def _safe_endpoint(value: str) -> str:
    text = str(value or "").strip()
    if not text:
        return ""
    try:
        parsed = urlsplit(text)
    except ValueError:
        return "invalid"
    host = parsed.hostname or ""
    if parsed.port:
        host = f"{host}:{parsed.port}"
    return urlunsplit((parsed.scheme, host, parsed.path, "", ""))


class DiagnosticsServiceMixin:
    def build_diagnostics_report(self) -> dict[str, Any]:
        model = self.get_model_settings()
        usage = shutil.disk_usage(self.storage_root)
        runs: list[dict[str, Any]] = []
        for manifest_path in sorted(self.runs_root.glob("*/run_manifest.json")):
            manifest = load_json_file(manifest_path)
            if not isinstance(manifest, dict):
                runs.append({"run_id": manifest_path.parent.name, "status": "invalid_manifest"})
                continue
            artifact_index = dict(manifest.get("artifact_index", {}) or {})
            progress = dict(manifest.get("progress", {}) or {})
            error = dict(manifest.get("error", {}) or {})
            runs.append(
                {
                    "run_id": str(manifest.get("run_id", "")).strip() or manifest_path.parent.name,
                    "status": str(manifest.get("status", "")).strip(),
                    "stage": str(progress.get("stage", "")).strip(),
                    "character_count": len(list(artifact_index.get("characters", []) or [])),
                    "error_type": str(error.get("type", "")).strip(),
                    "updated_at": str(manifest.get("updated_at", "")).strip(),
                }
            )
        profiles = [
            {
                "profile_id": str(item.get("profile_id", "")).strip(),
                "provider": str(item.get("provider", "")).strip(),
                "model": str(item.get("model", "")).strip(),
                "base_url": _safe_endpoint(str(item.get("base_url", ""))),
                "api_key_configured": bool(item.get("api_key_configured", False)),
                "configured": bool(item.get("configured", False)),
            }
            for item in list(model.get("profiles", []) or [])
        ]
        return {
            "kind": "zaomeng_diagnostics",
            "schema_version": 1,
            "generated_at": utc_now(),
            "runtime": {
                "python": platform.python_version(),
                "platform": sys.platform,
                "machine": platform.machine(),
            },
            "storage": {
                "run_count": len(runs),
                "free_bytes": usage.free,
                "total_bytes": usage.total,
            },
            "model": {
                "active_profile_id": str(model.get("active_profile_id", "")).strip(),
                "profiles": profiles,
            },
            "startup": load_json_file(Path(self.storage_root) / "android_startup_report.json") or {},
            "runs": runs,
        }


__all__ = ["DiagnosticsServiceMixin"]
