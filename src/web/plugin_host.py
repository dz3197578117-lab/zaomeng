from __future__ import annotations

from typing import Any

from src.plugin_system import PluginPermissionError


class ZaomengPluginHost:
    """Permission-checked host capabilities exposed to API v1 plugins."""

    def __init__(self, service: Any, plugin_id: str, permissions: frozenset[str]) -> None:
        self._service = service
        self._plugin_id = plugin_id
        self._permissions = permissions

    def _require(self, permission: str) -> None:
        if permission not in self._permissions:
            raise PluginPermissionError(
                f"Plugin {self._plugin_id!r} did not declare permission {permission!r}."
            )

    def read_dialogue_context(
        self,
        *,
        run_id: str,
        session_id: str,
        seed_text: str = "",
        direction: str = "",
    ) -> dict[str, Any]:
        self._require("chat.context.read")
        manifest = self._service._require_manifest(run_id)
        return self._service.dialogue.build_suggestion_payload(
            manifest,
            session_id=session_id,
            seed_text=seed_text,
            direction=direction,
        )

    def invoke_model(self, capability: str, payload: dict[str, Any]) -> Any:
        self._require("model.invoke")
        run_id = str(payload.get("run_id", "")).strip()
        if not run_id:
            raise ValueError("Plugin model payload has no run_id.")
        if capability == "dialogue_suggestion":
            return self._service._generate_dialogue_suggestion(run_id, payload)
        if capability == "dialogue_reply_variants":
            self._require("chat.context.read")
            session_id = str(payload.get("session_id", "")).strip()
            if not session_id:
                raise ValueError("Reply variants payload has no session_id.")
            option_count = max(2, min(4, int(payload.get("option_count", 3) or 3)))
            return self._service.associate_dialogue_turn(
                run_id,
                session_id=session_id,
                option_count=option_count,
            )
        if capability == "temporary_npc":
            self._require("chat.context.read")
            return self._service._generate_plugin_temporary_npc(run_id, payload)
        raise PluginPermissionError(
            f"Plugin model capability is not exposed by API v1: {capability!r}."
        )
