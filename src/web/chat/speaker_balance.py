from __future__ import annotations

import re
from typing import Any


def extract_mention_targets(
    active_participants: list[str], message: str
) -> list[str]:
    text = str(message or "")
    boundary = r"(?=$|[\s，。！？；：、（）(),.!?;:])"
    longest_at_position: dict[int, str] = {}
    for item in active_participants:
        name = str(item).strip()
        if not name:
            continue
        for match in re.finditer(r"@" + re.escape(name) + boundary, text):
            current = longest_at_position.get(match.start(), "")
            if len(name) > len(current):
                longest_at_position[match.start()] = name
    matched = set(longest_at_position.values())
    return [
        str(name).strip()
        for name in active_participants
        if str(name).strip()
        and str(name).strip() in matched
    ]


def build_speaker_activity(
    participants: list[str], completed_turns: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    names = [str(item).strip() for item in participants if str(item).strip()]
    total_turns = len(completed_turns)
    activity: list[dict[str, Any]] = []
    for name in names:
        spoken_turns: list[int] = []
        reply_count = 0
        for index, record in enumerate(completed_turns, start=1):
            responses = list(dict(record.get("result", {}) or {}).get("responses", []) or [])
            matches = [
                item
                for item in responses
                if str(dict(item or {}).get("speaker", "")).strip() == name
            ]
            if matches:
                spoken_turns.append(index)
                reply_count += len(matches)
        last_spoke_turn = spoken_turns[-1] if spoken_turns else 0
        turns_since_spoke = (
            total_turns - last_spoke_turn if last_spoke_turn else total_turns
        )
        if total_turns == 0:
            status = "new"
        elif turns_since_spoke >= 3:
            status = "silent"
        elif turns_since_spoke >= 2:
            status = "due"
        else:
            status = "active"
        activity.append(
            {
                "name": name,
                "reply_count": reply_count,
                "spoken_turns": len(spoken_turns),
                "total_turns": total_turns,
                "last_spoke_turn": last_spoke_turn,
                "turns_since_spoke": turns_since_spoke,
                "participation_rate": (
                    round(len(spoken_turns) / total_turns, 3)
                    if total_turns
                    else 0.0
                ),
                "status": status,
            }
        )
    return activity


def build_speaker_plan(
    *,
    activity: list[dict[str, Any]],
    active_participants: list[str],
    message: str,
    mode: str,
    input_speaker: str,
    controlled_character: str,
    message_kind: str,
    response_limit: int,
) -> dict[str, Any]:
    active = [
        str(item).strip()
        for item in active_participants
        if str(item).strip()
    ]
    speaker = str(input_speaker or "").strip()
    controlled = str(controlled_character or "").strip()
    kind = str(message_kind or "dialogue").strip()
    is_scene_kind = kind in {"narration", "plot"}
    eligible: list[str] = []
    for name in active:
        if mode == "act" and not is_scene_kind and name == speaker:
            continue
        if name not in eligible:
            eligible.append(name)
    by_name = {
        str(item.get("name", "")).strip(): dict(item or {})
        for item in activity
        if str(item.get("name", "")).strip()
    }
    text = str(message or "")
    direct_mentions = [
        name
        for name in extract_mention_targets(eligible, text)
        if not controlled or name != controlled
    ]

    def score(name: str) -> tuple[int, int, int, int, str]:
        row = by_name.get(name, {})
        mentioned = 2 if name in direct_mentions else 1 if name and name in text else 0
        silence = int(row.get("turns_since_spoke", 0) or 0)
        spoken_turns = int(row.get("spoken_turns", 0) or 0)
        controlled_penalty = 1 if is_scene_kind and name == controlled else 0
        return (-mentioned, -silence, controlled_penalty, spoken_turns, name)

    ordered = sorted(eligible, key=score)
    limit = max(1, min(int(response_limit or 1), len(ordered) or 1))
    recommended = ordered[:limit]
    silence_candidates = [
        name
        for name in ordered
        if int(by_name.get(name, {}).get("turns_since_spoke", 0) or 0) >= 2
    ][:limit]
    priority_candidates = list(
        dict.fromkeys([*direct_mentions, *silence_candidates])
    )[:limit]
    reasons: dict[str, str] = {}
    for name in ordered:
        row = by_name.get(name, {})
        if name in direct_mentions:
            reasons[name] = "本轮被 @ 直接点名"
        elif name in text:
            reasons[name] = "本轮被提及"
        elif int(row.get("turns_since_spoke", 0) or 0) >= 3:
            reasons[name] = f"已连续 {int(row.get('turns_since_spoke', 0) or 0)} 轮未发言"
        elif int(row.get("turns_since_spoke", 0) or 0) >= 2:
            reasons[name] = "近期较少参与"
        else:
            reasons[name] = "当前在场且适合回应"
    return {
        "order": ordered,
        "recommended_speakers": recommended,
        "mention_targets": direct_mentions,
        "priority_candidates": priority_candidates,
        "reasons": reasons,
        "response_limit": limit,
        "rule": (
            (
                f"用户明确 @ 了 {', '.join(direct_mentions)}；这些在场角色必须在本轮直接回应，且优先于未被点名的角色。"
                if direct_mentions
                else "优先让被点名、与当前行动直接相关或较久未发言的在场角色自然介入；"
            )
            + "不要为了平均分配而强迫无关角色说话。"
        ),
    }


def apply_plan_to_hints(
    hints: list[dict[str, str]], plan: dict[str, Any]
) -> list[dict[str, str]]:
    normalized_hints = [
        dict(item or {})
        for item in hints
        if str(item.get("name", "")).strip()
    ]
    urgent = set(plan.get("priority_candidates", []) or [])
    reasons = dict(plan.get("reasons", {}) or {})
    merged: list[dict[str, str]] = []
    for item in normalized_hints:
        name = str(item.get("name", "")).strip()
        if name in urgent:
            item["priority"] = "urgent"
        item["reason"] = str(reasons.get(name, "")).strip()
        merged.append(item)
    return merged


__all__ = [
    "apply_plan_to_hints",
    "build_speaker_activity",
    "build_speaker_plan",
    "extract_mention_targets",
]
