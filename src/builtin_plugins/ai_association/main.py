from __future__ import annotations

from typing import Any


class AiAssociationPlugin:
    def __init__(self) -> None:
        self._host: Any = None

    def activate(self, host: Any) -> None:
        self._host = host

    def deactivate(self) -> None:
        self._host = None

    def execute_chat_action(
        self, action_id: str, request: dict[str, Any]
    ) -> dict[str, str]:
        if action_id != "suggest-turn":
            raise ValueError(f"Unsupported AI association action: {action_id!r}.")
        if self._host is None:
            raise RuntimeError("AI association plugin is not active.")
        context = self._host.read_dialogue_context(
            run_id=str(request.get("run_id", "")).strip(),
            session_id=str(request.get("session_id", "")).strip(),
            seed_text=str(request.get("seed_text", "")),
            direction=str(request.get("direction", "")),
        )
        suggestion = self._host.invoke_model("dialogue_suggestion", context)
        return {"suggestion": str(suggestion).strip()}


def create_plugin() -> AiAssociationPlugin:
    return AiAssociationPlugin()
