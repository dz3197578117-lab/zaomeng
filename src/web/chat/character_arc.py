from __future__ import annotations

from typing import Any

from src.web.chat.text_utils import trim_summary_text


_TRACKED_FIELDS = (
    "mood",
    "interaction_state",
    "focus",
    "last_target",
    "present_state",
    "scene_location",
)

_FIELD_LABELS = {
    "mood": "情绪",
    "interaction_state": "立场",
    "focus": "目标",
    "last_target": "关注对象",
    "present_state": "在场状态",
    "scene_location": "位置",
}


def _normalized_snapshot(snapshot: dict[str, Any]) -> dict[str, str]:
    return {
        key: trim_summary_text(str(snapshot.get(key, "")).strip(), 120)
        for key in _TRACKED_FIELDS
        if str(snapshot.get(key, "")).strip()
    }


def _turn_reason(record: dict[str, Any], name: str, snapshot: dict[str, Any]) -> str:
    last_event = str(snapshot.get("last_event", "")).strip()
    if last_event:
        return trim_summary_text(last_event, 150)
    result = dict(record.get("result", {}) or {})
    for response in reversed(list(result.get("responses", []) or [])):
        if not isinstance(response, dict):
            continue
        if str(response.get("speaker", "")).strip() == name:
            return trim_summary_text(str(response.get("message", "")).strip(), 150)
    payload = dict(record.get("payload", {}) or {})
    return trim_summary_text(
        str(dict(payload.get("input", {}) or {}).get("message", "")).strip(), 150
    )


def build_character_arcs(
    participants: list[str],
    turn_records: list[dict[str, Any]],
    *,
    inherited_arcs: list[dict[str, Any]] | None = None,
) -> list[dict[str, Any]]:
    """Build compact per-character state histories from completed turn checkpoints."""

    names = [str(item).strip() for item in participants if str(item).strip()]
    inherited_map = {
        str(item.get("name", "")).strip(): dict(item or {})
        for item in list(inherited_arcs or [])
        if isinstance(item, dict) and str(item.get("name", "")).strip()
    }
    arcs: list[dict[str, Any]] = []
    for name in names:
        inherited = inherited_map.get(name, {})
        points = [
            dict(item or {})
            for item in list(inherited.get("points", []) or [])
            if isinstance(item, dict)
        ]
        previous = dict(points[-1].get("state", {}) or {}) if points else {}
        for index, record in enumerate(turn_records):
            checkpoint = dict(record.get("checkpoint", {}) or {})
            snapshots = dict(checkpoint.get("character_snapshots", {}) or {})
            raw_snapshot = dict(snapshots.get(name, {}) or {})
            state = _normalized_snapshot(raw_snapshot)
            if not state:
                continue
            changed_fields = [
                key for key in _TRACKED_FIELDS if state.get(key, "") != previous.get(key, "")
            ]
            if previous and not changed_fields:
                continue
            changes = [
                {
                    "field": key,
                    "label": _FIELD_LABELS[key],
                    "before": str(previous.get(key, "")).strip(),
                    "after": str(state.get(key, "")).strip(),
                }
                for key in changed_fields
            ]
            reason = _turn_reason(record, name, raw_snapshot)
            points.append(
                {
                    "turn_id": str(record.get("turn_id", "")).strip(),
                    "turn_number": len(points) + 1,
                    "state": state,
                    "changes": changes,
                    "reason": reason or "人物状态在这一轮发生了变化。",
                    "updated_at": str(record.get("updated_at", "")).strip(),
                    "inherited": False,
                }
            )
            previous = state
        current = dict(points[-1].get("state", {}) or {}) if points else {}
        latest_changes = list(points[-1].get("changes", []) or []) if points else []
        change_labels = [str(item.get("label", "")).strip() for item in latest_changes]
        arcs.append(
            {
                "name": name,
                "current": current,
                "points": points,
                "change_count": max(0, len(points) - 1),
                "growth_summary": (
                    f"最近变化：{'、'.join(change_labels)}"
                    if change_labels
                    else "尚未记录到明显的状态变化。"
                ),
            }
        )
    return arcs


__all__ = ["build_character_arcs"]
