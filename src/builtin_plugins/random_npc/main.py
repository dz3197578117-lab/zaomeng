from __future__ import annotations

from typing import Any


_STYLE_DIRECTIONS = {
    "mixed": "完全随机，但不要选择最常见、最稳妥的路人类型。",
    "mysterious": "生成一名带着未解目的或异常线索的神秘人物。",
    "funny": "生成一名有鲜明怪癖或反差感，但不破坏世界观的人物。",
    "troublesome": "生成一名会给当前人物制造具体麻烦或误会的人物。",
    "helpful": "生成一名愿意提供帮助，但有自身条件和目的的人物。",
    "dangerous": "生成一名带来明确威胁感，但不会无理由立刻攻击的人物。",
}


class RandomNpcPlugin:
    def __init__(self) -> None:
        self._host: Any = None

    def activate(self, host: Any) -> None:
        self._host = host

    def deactivate(self) -> None:
        self._host = None

    def generate_temporary_npc(
        self, generator_id: str, request: dict[str, Any]
    ) -> dict[str, dict[str, str]]:
        if generator_id != "generate-npc":
            raise ValueError(f"Unsupported temporary NPC generator: {generator_id!r}.")
        if self._host is None:
            raise RuntimeError("Random NPC plugin is not active.")
        config = dict(request.get("config", {}) or {})
        style = str(config.get("npcStyle", "mixed")).strip()
        direction = _STYLE_DIRECTIONS.get(style, _STYLE_DIRECTIONS["mixed"])
        user_direction = str(request.get("direction", "")).strip()
        if user_direction:
            direction = f"{direction} 用户补充方向：{user_direction}"
        context = self._host.read_dialogue_context(
            run_id=str(request.get("run_id", "")).strip(),
            session_id=str(request.get("session_id", "")).strip(),
            direction=direction,
        )
        context["npc_style"] = style
        context["direction"] = direction
        npc = self._host.invoke_model("temporary_npc", context)
        if not isinstance(npc, dict):
            raise ValueError("随机 NPC 模型能力没有返回人物对象。")
        return {"npc": {key: str(value).strip() for key, value in npc.items()}}


def create_plugin() -> RandomNpcPlugin:
    return RandomNpcPlugin()
