from __future__ import annotations

from typing import Any

from src.web.chat.text_utils import trim_summary_text


_HOOK_KEYWORDS = (
    "答应",
    "一定",
    "稍后",
    "之后",
    "明天",
    "今晚",
    "等我",
    "还没",
    "尚未",
    "秘密",
    "真相",
    "线索",
    "承诺",
)


def _event_texts(event: dict[str, Any]) -> list[str]:
    values = [
        str(event.get("user_message", "")).strip(),
        str(event.get("title", "")).strip(),
    ]
    values.extend(
        str(item.get("message", "")).strip()
        for item in list(event.get("responses", []) or [])
        if isinstance(item, dict)
    )
    return [value for value in values if value]


def _chapter_hooks(events: list[dict[str, Any]], fallback: str = "") -> list[str]:
    hooks: list[str] = []
    for event in reversed(events):
        for text in reversed(_event_texts(event)):
            if not any(keyword in text for keyword in _HOOK_KEYWORDS):
                continue
            hook = trim_summary_text(text, 72)
            if hook and hook not in hooks:
                hooks.append(hook)
            if len(hooks) >= 4:
                return list(reversed(hooks))
    fallback = trim_summary_text(str(fallback or "").strip(), 180)
    if not hooks and fallback:
        hooks.append(fallback)
    return list(reversed(hooks))


def _chapter_summary(
    scene: dict[str, Any], events: list[dict[str, Any]], fallback: str = ""
) -> str:
    beats: list[str] = []
    for event in events:
        title = trim_summary_text(str(event.get("title", "")).strip(), 64)
        if title and title not in beats:
            beats.append(title)
        if len(beats) >= 4:
            break
    if beats:
        return trim_summary_text("；".join(beats), 220)
    scene_memory = dict(scene.get("memory_summary", {}) or {})
    recap = str(scene_memory.get("recap", "") or fallback).strip()
    if recap:
        return trim_summary_text(recap, 220)
    transition = str(scene.get("transition_message", "")).strip()
    return trim_summary_text(transition or "本幕尚未发生明确事件。", 220)


def build_chapter_outline(
    scene_history: list[dict[str, Any]],
    event_timeline: list[dict[str, Any]],
    *,
    session_summary: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Group timeline events into scene-based chapters without mutating session data."""

    scenes = [dict(item or {}) for item in scene_history if isinstance(item, dict)]
    events = [dict(item or {}) for item in event_timeline if isinstance(item, dict)]
    if not scenes:
        scenes = [
            {
                "title": "未命名场景",
                "location": next(
                    (str(item.get("location", "")).strip() for item in events if str(item.get("location", "")).strip()),
                    "",
                ),
                "time_hint": next(
                    (str(item.get("time_hint", "")).strip() for item in events if str(item.get("time_hint", "")).strip()),
                    "",
                ),
                "ts": "",
            }
        ]

    buckets: list[list[dict[str, Any]]] = [[] for _ in scenes]
    scene_times = [str(item.get("ts", "")).strip() for item in scenes]
    for event in events:
        event_time = str(event.get("updated_at", "")).strip()
        target_index = 0
        for index, scene_time in enumerate(scene_times):
            if not scene_time or not event_time or scene_time <= event_time:
                target_index = index
            else:
                break
        buckets[target_index].append(event)

    summary = dict(session_summary or {})
    global_threads = str(summary.get("unresolved_threads", "")).strip()
    chapters: list[dict[str, Any]] = []
    for index, scene in enumerate(scenes):
        chapter_events = buckets[index]
        scene_card = dict(scene.get("scene_card", {}) or {})
        title = str(scene.get("title", "") or scene_card.get("title", "")).strip()
        location = str(scene.get("location", "") or scene_card.get("location", "")).strip()
        time_hint = str(scene.get("time_hint", "") or scene_card.get("time_hint", "")).strip()
        participants = list(
            dict.fromkeys(
                str(name).strip()
                for event in chapter_events
                for name in list(event.get("participants", []) or [])
                if str(name).strip()
            )
        )
        fallback_threads = global_threads if index == len(scenes) - 1 else ""
        chapters.append(
            {
                "chapter_number": index + 1,
                "scene_index": index,
                "scene_card_id": str(scene.get("scene_card_id", "")).strip(),
                "title": title or f"第 {index + 1} 幕",
                "location": location,
                "time_hint": time_hint,
                "atmosphere": str(
                    scene.get("atmosphere", "") or scene_card.get("atmosphere", "")
                ).strip(),
                "transition_message": str(scene.get("transition_message", "")).strip(),
                "summary": _chapter_summary(scene, chapter_events, fallback_threads),
                "hooks": _chapter_hooks(chapter_events, fallback_threads),
                "participants": participants,
                "event_count": len(chapter_events),
                "start_turn_id": (
                    str(chapter_events[0].get("turn_id", "")).strip()
                    if chapter_events
                    else ""
                ),
                "end_turn_id": (
                    str(chapter_events[-1].get("turn_id", "")).strip()
                    if chapter_events
                    else ""
                ),
                "is_current": index == len(scenes) - 1,
            }
        )
    return {
        "chapter_count": len(chapters),
        "event_count": len(events),
        "chapters": chapters,
        "unresolved_hook_count": sum(len(item["hooks"]) for item in chapters),
    }


__all__ = ["build_chapter_outline"]
