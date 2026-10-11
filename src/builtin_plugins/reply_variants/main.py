from __future__ import annotations

from typing import Any


class ReplyVariantsPlugin:
    def __init__(self) -> None:
        self._host: Any = None

    def activate(self, host: Any) -> None:
        self._host = host

    def deactivate(self) -> None:
        self._host = None

    def execute_chat_action(
        self, action_id: str, request: dict[str, Any]
    ) -> dict[str, list[dict[str, str]]]:
        if action_id != "generate-variants":
            raise ValueError(f"Unsupported reply variants action: {action_id!r}.")
        if self._host is None:
            raise RuntimeError("Reply variants plugin is not active.")
        config = dict(request.get("config", {}) or {})
        option_count = max(2, min(int(config.get("optionCount", 3)), 4))
        result = self._host.invoke_model(
            "dialogue_reply_variants",
            {
                "run_id": str(request.get("run_id", "")).strip(),
                "session_id": str(request.get("session_id", "")).strip(),
                "option_count": option_count,
            },
        )
        suggestions = []
        for option in list(result.get("options", []) or []):
            if not isinstance(option, dict):
                continue
            suggestion = str(option.get("suggestion", "")).strip()
            if not suggestion:
                continue
            suggestions.append(
                {
                    "label": str(option.get("label", "")).strip() or "候选回复",
                    "suggestion": suggestion,
                }
            )
        if not suggestions:
            raise ValueError("多候选回复插件没有生成可用候选。")
        return {"suggestions": suggestions}


def create_plugin() -> ReplyVariantsPlugin:
    return ReplyVariantsPlugin()
