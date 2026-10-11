from __future__ import annotations

import base64
import binascii
import json
import shutil
import tempfile
from pathlib import Path
from typing import Any
from uuid import uuid4

from src.core.exceptions import LLMRequestError
from src.plugin_system import PluginError
from src.plugin_system.packages import (
    extract_plugin_archive,
    inspect_plugin_archive,
    read_stage_metadata,
    write_stage_metadata,
)
from src.web.chat import friendly_dialogue_llm_error
from src.web.time_utils import utc_now


def _move_plugin_directory(source: Path, target: Path) -> None:
    """Atomically move a staged plugin into place on the same storage volume."""
    source.replace(target)


class PluginServiceMixin:
    def list_plugins(self) -> list[dict[str, Any]]:
        return self.plugins.list_plugins()

    def refresh_plugins(self) -> list[dict[str, Any]]:
        return self.plugins.refresh()

    def enable_plugin(self, plugin_id: str) -> dict[str, Any]:
        return self.plugins.enable(plugin_id)

    def disable_plugin(self, plugin_id: str) -> dict[str, Any]:
        return self.plugins.disable(plugin_id)

    def list_plugin_logs(self, plugin_id: str, *, limit: int = 100) -> dict[str, Any]:
        return {"items": self.plugins.list_logs(plugin_id, limit=limit)}

    def get_plugin_config(self, plugin_id: str) -> dict[str, Any]:
        return {"config": self.plugins.get_config(plugin_id)}

    def update_plugin_config(
        self, plugin_id: str, config: dict[str, Any]
    ) -> dict[str, Any]:
        return {"config": self.plugins.update_config(plugin_id, config)}

    @property
    def _plugin_staging_root(self):
        root = self.storage_root / "plugin-staging"
        root.mkdir(parents=True, exist_ok=True)
        return root

    @property
    def _user_plugins_root(self):
        root = self.storage_root / "plugins"
        root.mkdir(parents=True, exist_ok=True)
        return root

    def inspect_plugin_package(
        self, *, filename: str, content_base64: str
    ) -> dict[str, Any]:
        try:
            data = base64.b64decode(str(content_base64 or ""), validate=True)
        except (ValueError, binascii.Error) as exc:
            raise PluginError("插件包内容不是有效的 Base64 数据。") from exc
        token = uuid4().hex
        package_path = self._plugin_staging_root / f"{token}.zip"
        metadata_path = self._plugin_staging_root / f"{token}.json"
        package_path.write_bytes(data)
        try:
            inspected = inspect_plugin_archive(package_path)
            manifest = inspected.manifest.to_dict()
            duplicate = next(
                (
                    item
                    for item in self.plugins.list_plugins()
                    if item.get("id") == inspected.manifest.id
                ),
                None,
            )
            operation = "install"
            blocked_reason = ""
            if not inspected.compatible:
                operation = "blocked"
                blocked_reason = (
                    f"插件需要 API {inspected.manifest.api_version}，"
                    "当前应用仅支持 API 1。"
                )
            elif duplicate:
                if duplicate.get("source") == "official":
                    operation = "blocked"
                    blocked_reason = "内置插件不能由外部插件包覆盖。"
                else:
                    operation = "update"
            metadata = {
                "token": token,
                "filename": str(filename or "plugin.zip")[:200],
                "plugin_id": inspected.manifest.id,
                "root_prefix": inspected.root_prefix,
                "operation": operation,
            }
            write_stage_metadata(metadata_path, metadata)
            return {
                "token": token,
                "plugin": manifest,
                "operation": operation,
                "blockedReason": blocked_reason,
                "currentVersion": str((duplicate or {}).get("version", "")),
                "compatible": inspected.compatible,
                "hostApiVersion": "1",
                "fileCount": inspected.file_count,
                "extractedBytes": inspected.extracted_bytes,
            }
        except Exception:
            package_path.unlink(missing_ok=True)
            metadata_path.unlink(missing_ok=True)
            raise

    def install_inspected_plugin_package(
        self,
        token: str,
        *,
        confirm_permissions: bool,
        allow_update: bool,
    ) -> dict[str, Any]:
        normalized_token = str(token or "").strip().lower()
        if len(normalized_token) != 32 or any(
            char not in "0123456789abcdef" for char in normalized_token
        ):
            raise PluginError("插件安装会话标识无效。")
        if not confirm_permissions:
            raise PluginError("必须确认插件权限后才能安装。")
        package_path = self._plugin_staging_root / f"{normalized_token}.zip"
        metadata_path = self._plugin_staging_root / f"{normalized_token}.json"
        metadata = read_stage_metadata(metadata_path)
        inspected = inspect_plugin_archive(package_path)
        if not inspected.compatible:
            raise PluginError(
                f"插件需要 API {inspected.manifest.api_version}，当前应用仅支持 API 1。"
            )
        plugin_id = inspected.manifest.id
        if plugin_id != str(metadata.get("plugin_id", "")):
            raise PluginError("插件包内容在确认后发生变化，请重新检查。")
        duplicate = next(
            (item for item in self.plugins.list_plugins() if item.get("id") == plugin_id),
            None,
        )
        if duplicate and duplicate.get("source") == "official":
            raise PluginError("内置插件不能由外部插件包覆盖。")
        if duplicate and not allow_update:
            raise PluginError("插件 ID 已存在；请明确选择更新现有插件。")
        if not duplicate and allow_update:
            raise PluginError("要更新的插件已不存在，请重新检查插件包。")
        target = self._user_plugins_root / plugin_id
        if duplicate:
            target, source = self.plugins.plugin_location(plugin_id)
            if source != "third-party":
                raise PluginError("只能更新第三方插件。")
        elif target.exists():
            raise PluginError("插件目标目录已存在，但没有可识别的插件清单。")

        backup = self._plugin_staging_root / f"backup-{normalized_token}"
        try:
            with tempfile.TemporaryDirectory(
                prefix="plugin-install-", dir=self._plugin_staging_root
            ) as tmp:
                incoming = Path(tmp) / "plugin"
                extract_plugin_archive(
                    package_path, incoming, root_prefix=inspected.root_prefix
                )
                if target.exists():
                    target.replace(backup)
                _move_plugin_directory(incoming, target)
            self.plugins.refresh()
            installed = next(
                (item for item in self.plugins.list_plugins() if item.get("id") == plugin_id),
                None,
            )
            if not installed:
                raise PluginError("插件文件已写入，但刷新后没有发现该插件。")
            self.plugins.record_event(
                plugin_id,
                "info",
                "updated" if duplicate else "installed",
                "插件已更新。" if duplicate else "插件已安装。",
                details={"version": inspected.manifest.version},
            )
            shutil.rmtree(backup, ignore_errors=True)
            return installed
        except Exception as exc:
            if target.exists():
                shutil.rmtree(target, ignore_errors=True)
            if backup.exists():
                backup.replace(target)
            self.plugins.refresh()
            if isinstance(exc, PluginError):
                raise
            raise PluginError(f"插件安装失败：{exc}") from exc
        finally:
            package_path.unlink(missing_ok=True)
            metadata_path.unlink(missing_ok=True)

    def uninstall_plugin(self, plugin_id: str) -> dict[str, Any]:
        root, source = self.plugins.plugin_location(plugin_id)
        if source != "third-party":
            raise PluginError("内置插件不能卸载，只能停用。")
        self.plugins.disable(plugin_id)
        trash_root = self.storage_root / "plugin-trash"
        trash_root.mkdir(parents=True, exist_ok=True)
        target = trash_root / f"{plugin_id}-{uuid4().hex[:8]}"
        root.replace(target)
        self.plugins.refresh()
        self.plugins.record_event(
            plugin_id,
            "info",
            "uninstalled",
            "插件已卸载。",
        )
        return {
            "status": "uninstalled",
            "pluginId": plugin_id,
            "recoverablePath": str(target),
            "uninstalledAt": utc_now(),
        }

    def invoke_plugin_chat_action(
        self,
        plugin_id: str,
        action_id: str,
        *,
        run_id: str,
        session_id: str,
        seed_text: str = "",
        direction: str = "",
    ) -> dict[str, Any]:
        self._require_manifest(run_id)
        try:
            return self.plugins.invoke_chat_action(
                plugin_id,
                action_id,
                {
                    "run_id": run_id,
                    "session_id": session_id,
                    "seed_text": seed_text,
                    "direction": direction,
                },
            )
        except LLMRequestError as exc:
            raise ValueError(friendly_dialogue_llm_error(exc)) from exc

    def invoke_plugin_temporary_npc_generator(
        self,
        plugin_id: str,
        generator_id: str,
        *,
        run_id: str,
        session_id: str,
        direction: str = "",
    ) -> dict[str, Any]:
        manifest = self._require_manifest(run_id)
        try:
            result = self.plugins.invoke_temporary_npc_generator(
                plugin_id,
                generator_id,
                {
                    "run_id": run_id,
                    "session_id": session_id,
                    "direction": direction,
                },
            )
        except LLMRequestError as exc:
            raise ValueError(friendly_dialogue_llm_error(exc)) from exc
        npc = dict(result.get("npc", {}) or {})
        if not npc:
            raise PluginError("临时 NPC 生成器没有返回 npc 对象。")
        npc_name = str(npc.get("name", "")).strip()
        official_names = {
            str(item.get("name", "")).strip().casefold()
            for item in list(
                dict(manifest.get("artifact_index", {}) or {}).get("characters", [])
                if isinstance(manifest, dict)
                else []
            )
            if isinstance(item, dict) and str(item.get("name", "")).strip()
        }
        if npc_name.casefold() in official_names:
            raise PluginError(f"“{npc_name}”是正式角色，不能作为临时 NPC 加入。")
        session = self.dialogue.add_temporary_npc(run_id, session_id, npc)
        name = str(npc.get("name", "")).strip() or "新角色"
        return {
            "npc": npc,
            "session": session,
            "notice": f"{name}已加入当前场景。",
        }

    def _generate_plugin_temporary_npc(
        self, run_id: str, payload: dict[str, Any]
    ) -> dict[str, str]:
        run_dir = self.runs_root / run_id
        config = self._build_runtime_config_for_run(run_dir=run_dir)
        parts = self._build_runtime_parts(config)
        if not hasattr(parts.llm, "chat_completion"):
            raise ValueError("Configured model does not support chat generation.")
        input_payload = dict(payload.get("input", {}) or {})
        compact_context = {
            "mode": str(payload.get("mode", "observe")),
            "participants": list(input_payload.get("participants", []) or []),
            "active_participants": list(
                input_payload.get("active_participants", []) or []
            ),
            "scene": dict(payload.get("scene_progress", {}) or {}),
            "recent_history": [
                {
                    "speaker": str(dict(item or {}).get("speaker", ""))[:40],
                    "message": str(dict(item or {}).get("message", ""))[:500],
                }
                for item in list(payload.get("history", []) or [])[-8:]
                if isinstance(item, dict)
            ],
            "characters": [
                {
                    "name": str(dict(item or {}).get("name", ""))[:40],
                    "identity": str(
                        dict(dict(item or {}).get("preview", {}) or {}).get(
                            "core_identity", ""
                        )
                    )[:240],
                }
                for item in list(payload.get("persona_contexts", []) or [])[:8]
                if isinstance(item, dict)
            ],
            "style": str(payload.get("npc_style", "mixed"))[:40],
            "direction": str(payload.get("direction", ""))[:240],
        }
        messages = [
            {
                "role": "system",
                "content": (
                    "你为当前角色扮演场景随机创造一名临时 NPC。角色必须符合当前世界观、"
                    "地点和人物认知边界，不能与已有角色重名，不能成为主角，也不能凭空掌握"
                    "不该知道的秘密。让角色有鲜明但简洁的外观、性格、说话方式和当下动机，"
                    "其入场必须立刻制造可互动的钩子。只返回一个 JSON 对象，不要 Markdown。"
                ),
            },
            {
                "role": "user",
                "content": json.dumps(
                    {
                        "context": compact_context,
                        "output": {
                            "name": "具体且符合场景的名字或身份称呼",
                            "role": "在当前世界中的身份",
                            "appearance": "一个鲜明外观特征",
                            "personality": "简短性格",
                            "speech_style": "说话方式",
                            "motive": "此刻出现的真实目的",
                            "entrance": "一至两句入场描写",
                            "opening_line": "第一句可互动台词",
                        },
                    },
                    ensure_ascii=False,
                ),
            },
        ]
        configured_limit = int(config.get("llm.max_tokens", 0) or 0)
        max_tokens = min(configured_limit, 700) if configured_limit > 0 else 700
        last_error: Exception | None = None
        for attempt in range(2):
            attempt_messages = list(messages)
            if attempt:
                attempt_messages.append(
                    {
                        "role": "system",
                        "content": (
                            "上一次输出不是有效人物 JSON。重新生成，只返回一个完整 JSON 对象，"
                            "必须包含 name 和 opening_line。"
                        ),
                    }
                )
            result = parts.llm.chat_completion(
                attempt_messages,
                temperature=0.9,
                max_tokens=max_tokens,
            )
            try:
                return self._parse_plugin_temporary_npc(
                    str(dict(result or {}).get("content", ""))
                )
            except ValueError as exc:
                last_error = exc
        raise ValueError(str(last_error or "模型没有返回有效的临时 NPC。"))

    @staticmethod
    def _parse_plugin_temporary_npc(content: str) -> dict[str, str]:
        text = str(content or "").strip()
        decoder = json.JSONDecoder()
        parsed: Any = None
        for index, character in enumerate(text):
            if character != "{":
                continue
            try:
                candidate, _end = decoder.raw_decode(text[index:])
            except json.JSONDecodeError:
                continue
            if isinstance(candidate, dict):
                parsed = candidate
                break
        if not isinstance(parsed, dict):
            raise ValueError("模型没有返回有效的临时 NPC JSON。")
        fields = (
            "name",
            "role",
            "appearance",
            "personality",
            "speech_style",
            "motive",
            "entrance",
            "opening_line",
        )
        npc = {key: str(parsed.get(key, "")).strip() for key in fields}
        if not npc["name"] or not npc["opening_line"]:
            raise ValueError("模型生成的临时 NPC 缺少名称或入场台词。")
        return npc

    @staticmethod
    def _generation_enhancer_key(plugin_id: str, enhancer_id: str) -> str:
        return f"{plugin_id}/{enhancer_id}"

    def set_generation_enhancer_state(
        self,
        plugin_id: str,
        enhancer_id: str,
        *,
        run_id: str,
        session_id: str,
        enabled: bool,
    ) -> dict[str, Any]:
        self._require_manifest(run_id)
        self.plugins.require_generation_enhancer(plugin_id, enhancer_id)
        return self.dialogue.set_plugin_enhancer_state(
            run_id,
            session_id,
            self._generation_enhancer_key(plugin_id, enhancer_id),
            enabled,
        )

    def resolve_generation_enhancer_options(
        self, run_id: str, session_id: str
    ) -> dict[str, Any]:
        session = self.dialogue.get_session(run_id, session_id)
        states = dict(session.get("plugin_enhancer_states", {}) or {})
        options: dict[str, Any] = {}
        for plugin in self.plugins.list_plugins():
            if not plugin.get("enabled"):
                continue
            plugin_id = str(plugin.get("id", "")).strip()
            plugin_config = dict(plugin.get("config", {}) or {})
            contributes = dict(plugin.get("contributes", {}) or {})
            for enhancer in list(contributes.get("generationEnhancers", []) or []):
                enhancer_id = str(enhancer.get("id", "")).strip()
                key = self._generation_enhancer_key(plugin_id, enhancer_id)
                active = bool(
                    states.get(
                        key,
                        plugin_config.get(
                            "defaultActive", enhancer.get("defaultActive", False)
                        ),
                    )
                )
                if not active:
                    continue
                try:
                    result = self.plugins.invoke_generation_enhancer(
                        plugin_id,
                        enhancer_id,
                        {"run_id": run_id, "session_id": session_id},
                    )
                except Exception:
                    continue
                options.update(result)
        return options
