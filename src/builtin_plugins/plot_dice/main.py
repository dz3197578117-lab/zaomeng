from __future__ import annotations

from typing import Any


_EVENT_DIRECTIONS = {
    "mixed": (
        "从意外、秘密、关系、来客、环境、抉择、失去、获得、流言、障碍、"
        "征兆、时限、干扰中随机选择一种，不要总选择最稳妥或最常见的发展。"
    ),
    "accident": "生成一次意外事故、失误、异常发现或计划外变化。",
    "secret": "让一个秘密、误解、线索或被隐瞒的事实开始浮出水面。",
    "relationship": "制造一次会改变人物关系张力的事件，而不只是闲聊。",
    "visitor": "安排一名符合世界观和当前地点的来客、信使或闯入者出现。",
    "environment": "让天气、地点、物件或周围环境发生能影响人物的变化。",
    "choice": (
        "抛出一个符合当前处境的两难抉择，角色需要尽快取舍，"
        "两种选择都应有明确代价。"
    ),
    "loss": (
        "让角色暂时失去一件现有物品、机会、能力或重要信息；"
        "不要凭空删除核心设定或永久剥夺关键能力。"
    ),
    "gain": (
        "让角色意外获得符合世界观的道具、情报、临时能力、帮助或资源；"
        "收益应伴随限制、疑点或后续影响。"
    ),
    "rumor": (
        "传来一条与当前人物或事件有关、真假不明的流言，影响角色判断，"
        "但不要直接确认其真实性。"
    ),
    "obstacle": (
        "在当前行动方向上设置一个具体障碍，例如封锁、机关、规则限制或人为阻拦，"
        "并保留解决空间。"
    ),
    "signal": (
        "出现一个符合当前世界观但暂时难以解释的异象、征兆、暗号或信号，"
        "为后续调查留下线索。"
    ),
    "time_limit": (
        "让当前事态产生明确但不过度精确的时间压力，"
        "留给角色行动和取舍的窗口有限。"
    ),
    "distraction": (
        "用一个与当前场景有关的突发干扰打断对话或行动，"
        "暂时转移在场人物的注意力。"
    ),
}


class PlotDicePlugin:
    def __init__(self) -> None:
        self._host: Any = None

    def activate(self, host: Any) -> None:
        self._host = host

    def deactivate(self) -> None:
        self._host = None

    def execute_chat_action(
        self, action_id: str, request: dict[str, Any]
    ) -> dict[str, str]:
        if action_id != "roll-plot":
            raise ValueError(f"Unsupported plot dice action: {action_id!r}.")
        if self._host is None:
            raise RuntimeError("Plot dice plugin is not active.")

        config = dict(request.get("config", {}) or {})
        event_type = str(config.get("eventType", "mixed")).strip()
        event_direction = _EVENT_DIRECTIONS.get(event_type, _EVENT_DIRECTIONS["mixed"])
        context = self._host.read_dialogue_context(
            run_id=str(request.get("run_id", "")).strip(),
            session_id=str(request.get("session_id", "")).strip(),
            seed_text=str(request.get("seed_text", "")).strip(),
            direction=(
                "你正在掷一枚剧情骰子。结合当前参与者、人物关系、最近对话、"
                "场景位置与已知事实，随机生成一个立刻发生并能推动故事的具体事件。"
                + event_direction
                + "事件必须符合当前世界观和人物认知边界，不得强行召回已经离场或不可能出现的人物。"
                "不要写角色的完整回复，不要解释设计思路，不要给多个选项；"
                "只返回一段简短、可编辑、可直接作为剧情指令发送的成品文本。"
            ),
        )
        suggestion = str(
            self._host.invoke_model("dialogue_suggestion", context)
        ).strip()
        if not suggestion:
            raise ValueError("剧情骰子没有生成可用事件，请再掷一次。")
        return {"suggestion": suggestion}


def create_plugin() -> PlotDicePlugin:
    return PlotDicePlugin()
