from __future__ import annotations

import re
from typing import Any

import src.web.chat.scene_signals as _scene_signals


_NARRATOR_NAMES = {"旁白", "场景提示", "User"}
_NEGATION_MARKERS = ("不", "不会", "不能", "并未", "没有", "从未", "绝不", "别", "勿")
_FORBIDDEN_PREFIX_RE = re.compile(
    r"^(?:禁止|不得|不能|不会|不应|避免|拒绝|从不|绝不|不要|不可|不)[：:、，,\s]*"
)
_SECRET_PATTERNS = (
    re.compile(r"(?:秘密|真相|实情|底牌)(?:是|为|：|:)\s*([^。！？!?\n]{4,80})"),
    re.compile(r"(?:只告诉你|别告诉别人|不要告诉别人)[，,:：]?\s*([^。！？!?\n]{4,80})"),
)
_CURRENT_TIME_MARKERS = ("现在", "此刻", "眼下", "如今", "已是", "已经到了", "天色")


def _normalized_names(values: Any) -> list[str]:
    result: list[str] = []
    for value in list(values or []):
        name = str(value or "").strip()
        if name and name not in result:
            result.append(name)
    return result


def _forbidden_phrases(value: Any) -> list[str]:
    raw_items = list(value) if isinstance(value, list) else [value]
    phrases: list[str] = []
    for raw in raw_items:
        for part in re.split(r"[\n；;。]+", str(raw or "")):
            phrase = _FORBIDDEN_PREFIX_RE.sub("", part.strip()).strip("：:、，,。 ")
            if len(phrase) >= 4 and phrase not in phrases:
                phrases.append(phrase)
    return phrases


def _is_negated(message: str, phrase: str) -> bool:
    index = message.find(phrase)
    if index < 0:
        return False
    prefix = message[max(0, index - 5) : index]
    return any(marker in prefix for marker in _NEGATION_MARKERS)


def _event_excerpt(pending_payload: dict[str, Any]) -> list[dict[str, Any]]:
    memory_context = dict(pending_payload.get("memory_context", {}) or {})
    return [
        dict(item or {})
        for item in list(memory_context.get("event_signals", []) or [])
        if isinstance(item, dict)
    ]


def _latest_presence_event(
    events: list[dict[str, Any]], speaker: str
) -> dict[str, Any]:
    for event in reversed(events):
        if str(event.get("actor", "")).strip() != speaker:
            continue
        if str(event.get("kind", "")).strip() in {"cast_enter", "cast_exit"}:
            return event
    return {}


def _knowledge_evidence(message: str, fact: str) -> str:
    compact_fact = re.sub(r"\s+", "", str(fact or ""))
    compact_message = re.sub(r"\s+", "", str(message or ""))
    if len(compact_fact) < 5 or len(compact_message) < 5:
        return ""
    if compact_fact in compact_message:
        return compact_fact[:24]
    window = min(12, len(compact_fact))
    for size in range(window, 4, -1):
        for index in range(0, len(compact_fact) - size + 1):
            candidate = compact_fact[index : index + size]
            if candidate in compact_message:
                return candidate
    return ""


def _extract_secret_facts(message: str) -> list[str]:
    facts: list[str] = []
    for pattern in _SECRET_PATTERNS:
        for match in pattern.finditer(str(message or "")):
            fact = match.group(1).strip(" ，,：:；;")
            if len(fact) >= 4 and fact not in facts:
                facts.append(fact)
    return facts


def evaluate_turn_consistency(
    pending_payload: dict[str, Any],
    responses: list[dict[str, Any]],
    *,
    checked_at: str,
) -> dict[str, Any]:
    """Run deterministic high-confidence checks without another model request."""

    input_payload = dict(pending_payload.get("input", {}) or {})
    mode = str(pending_payload.get("mode", input_payload.get("mode", "observe"))).strip()
    participants = _normalized_names(input_payload.get("participants", []))
    participant_set = set(participants)
    controlled = str(input_payload.get("controlled_character", "")).strip()
    message_kind = str(input_payload.get("message_kind", "dialogue")).strip()
    is_director_beat = message_kind in {"narration", "plot"}
    scene_progress = dict(
        pending_payload.get("scene_progress", input_payload.get("scene_progress", {})) or {}
    )
    offstage = set(_normalized_names(scene_progress.get("offstage_participants", [])))
    current_location = str(scene_progress.get("location", "")).strip()
    current_time = str(scene_progress.get("time_hint", "")).strip()
    character_snapshots = dict(input_payload.get("character_snapshots", {}) or {})
    presence_events = _event_excerpt(pending_payload)
    knowledge_records = [
        dict(item or {})
        for item in list(pending_payload.get("knowledge_context", []) or [])
        if isinstance(item, dict)
    ]

    persona_map: dict[str, dict[str, Any]] = {}
    for context in list(pending_payload.get("persona_contexts", []) or []):
        name = str((context or {}).get("name", "")).strip()
        if name:
            persona_map[name] = dict((context or {}).get("profile", {}) or {})

    issues: list[dict[str, str]] = []
    seen_issue_keys: set[tuple[str, str, str]] = set()

    def add_issue(
        code: str,
        speaker: str,
        title: str,
        detail: str,
        *,
        evidence: str = "",
        severity: str = "warning",
    ) -> None:
        key = (code, speaker, evidence)
        if key in seen_issue_keys:
            return
        seen_issue_keys.add(key)
        issues.append(
            {
                "code": code,
                "severity": severity,
                "speaker": speaker,
                "title": title,
                "detail": detail,
                "evidence": evidence,
            }
        )

    for response in responses:
        speaker = str(response.get("speaker", "")).strip()
        message = str(response.get("message", "")).strip()
        if not speaker or not message:
            continue
        if speaker not in participant_set and speaker not in _NARRATOR_NAMES:
            add_issue(
                "speaker_out_of_scope",
                speaker,
                "角色不在本场名单中",
                f"{speaker} 不属于当前会话角色，可能是模型误引入的人物。",
                evidence=speaker,
                severity="error",
            )
        if speaker in offstage:
            add_issue(
                "offstage_character_spoke",
                speaker,
                "离场角色突然发言",
                f"场景状态记录 {speaker} 已离场，但这一轮仍出现了其台词。",
                evidence=message[:80],
                severity="error",
            )
        snapshot = dict(character_snapshots.get(speaker, {}) or {})
        if str(snapshot.get("present_state", "")).strip() == "offstage":
            add_issue(
                "snapshot_marks_character_offstage",
                speaker,
                "人物快照仍标记为离场",
                f"{speaker} 的当前人物快照为离场状态，这一轮发言可能破坏场景连续性。",
                evidence=message[:80],
                severity="error",
            )
        snapshot_location = str(snapshot.get("scene_location", "")).strip()
        if (
            current_location
            and snapshot_location
            and snapshot_location != current_location
        ):
            add_issue(
                "character_location_mismatch",
                speaker,
                "人物位置与当前场景不一致",
                f"当前场景位于“{current_location}”，但 {speaker} 的人物快照仍在“{snapshot_location}”。",
                evidence=f"{snapshot_location} -> {current_location}",
                severity="error",
            )
        latest_presence = _latest_presence_event(presence_events, speaker)
        if str(latest_presence.get("kind", "")).strip() == "cast_exit":
            add_issue(
                "character_spoke_after_exit_event",
                speaker,
                "角色在离场事件后再次发言",
                f"最近的角色事件记录 {speaker} 已离场，且尚未出现重新入场事件。",
                evidence=str(latest_presence.get("cue", "")).strip()[:80],
                severity="error",
            )
        if (
            mode == "act"
            and controlled
            and speaker == controlled
            and not is_director_beat
        ):
            add_issue(
                "controlled_character_overwritten",
                speaker,
                "系统代写了受控角色",
                f"当前由用户扮演 {controlled}，系统回复不应替该角色发言。",
                evidence=message[:80],
                severity="error",
            )

        if current_time and any(marker in message for marker in _CURRENT_TIME_MARKERS):
            claimed_time = _scene_signals.infer_time_hint(
                [{"message": message}], include_character_claims=True
            )
            current_rank = _scene_signals.time_hint_rank(current_time)
            claimed_rank = _scene_signals.time_hint_rank(claimed_time)
            if claimed_rank >= 0 and current_rank >= 0 and claimed_rank < current_rank:
                add_issue(
                    "time_regression_claim",
                    speaker,
                    "台词中的当前时间发生倒退",
                    f"场景时间已经推进到“{current_time}”，但台词又把此刻描述成“{claimed_time}”。",
                    evidence=message[:80],
                    severity="error",
                )

        profile = persona_map.get(speaker, {})
        for phrase in _forbidden_phrases(profile.get("forbidden_behaviors", [])):
            if phrase in message and not _is_negated(message, phrase):
                add_issue(
                    "forbidden_behavior_overlap",
                    speaker,
                    "疑似触碰人物行为禁忌",
                    f"人物卡将“{phrase}”列为禁止或避免行为，请确认当前情境是否足以解释这一偏离。",
                    evidence=phrase,
                )

        for record in knowledge_records:
            holders = set(_normalized_names(record.get("holders", [])))
            if not holders or speaker in holders:
                continue
            fact = str(record.get("fact", "")).strip()
            evidence = _knowledge_evidence(message, fact)
            if not evidence:
                continue
            add_issue(
                "knowledge_boundary_violation",
                speaker,
                "角色疑似知道了未获知的秘密",
                f"“{evidence}”来自一条受限信息，但当前知识账本中不包含 {speaker}。",
                evidence=evidence,
                severity="error",
            )

    error_count = sum(item["severity"] == "error" for item in issues)
    warning_count = len(issues) - error_count
    score = max(0, 100 - error_count * 25 - warning_count * 10)
    status = "pass" if not issues else "warning"
    summary = (
        "本轮未发现高置信度的人设或场景越界。"
        if not issues
        else f"本轮发现 {len(issues)} 个潜在一致性问题，建议复核后再继续。"
    )
    return {
        "status": status,
        "score": score,
        "summary": summary,
        "turn_id": str(pending_payload.get("turn_id", "")).strip(),
        "checked_at": checked_at,
        "issues": issues,
        "coverage": {
            "speaker_scope": True,
            "scene_presence": True,
            "control_boundary": True,
            "forbidden_behavior": True,
            "event_continuity": True,
            "time_location_continuity": True,
            "knowledge_boundary": True,
            "persona_profiles": len(persona_map),
            "knowledge_records": len(knowledge_records),
            "semantic_review": False,
        },
    }


def update_monitor_state(
    current: dict[str, Any] | None, report: dict[str, Any]
) -> dict[str, Any]:
    state = dict(current or {})
    history = [dict(item or {}) for item in list(state.get("history", []) or [])]
    history.append(report)
    bounded_history = history[-20:]
    return {
        "latest": report,
        "history": bounded_history,
        "checked_turns": int(state.get("checked_turns", 0) or 0) + 1,
        "issue_count": int(state.get("issue_count", 0) or 0)
        + len(list(report.get("issues", []) or [])),
        "metrics": build_monitor_metrics(bounded_history),
    }


def _issue_category(code: str) -> str:
    normalized = str(code or "").strip()
    if normalized in {"knowledge_boundary_violation", "semantic_knowledge_drift"}:
        return "knowledge_boundary"
    if normalized in {
        "forbidden_behavior_overlap",
        "semantic_voice_drift",
        "semantic_motivation_drift",
    }:
        return "persona_taboo"
    if normalized == "semantic_relationship_drift":
        return "relationship_attitude"
    if normalized in {
        "offstage_character_spoke",
        "snapshot_marks_character_offstage",
        "character_spoke_after_exit_event",
        "character_location_mismatch",
        "time_regression_claim",
    }:
        return "scene_continuity"
    if normalized in {"speaker_out_of_scope", "controlled_character_overwritten"}:
        return "role_boundary"
    return "other"


def build_monitor_metrics(history: list[dict[str, Any]] | None) -> dict[str, Any]:
    def score_value(value: Any) -> int:
        try:
            return max(0, min(100, int(value or 0)))
        except (TypeError, ValueError):
            return 0

    reports = [
        dict(item or {})
        for item in list(history or [])
        if isinstance(item, dict) and str(item.get("status", "")).strip()
    ]
    checked_turns = len(reports)
    scores = [score_value(item.get("score", 0)) for item in reports]
    passed_turns = sum(not list(item.get("issues", []) or []) for item in reports)
    issue_turns = checked_turns - passed_turns
    current_pass_streak = 0
    for item in reversed(reports):
        if list(item.get("issues", []) or []):
            break
        current_pass_streak += 1

    category_counts: dict[str, int] = {}
    total_issues = 0
    for report in reports:
        for issue in list(report.get("issues", []) or []):
            if not isinstance(issue, dict):
                continue
            category = _issue_category(str(issue.get("code", "")))
            category_counts[category] = category_counts.get(category, 0) + 1
            total_issues += 1

    return {
        "checked_turns": checked_turns,
        "passed_turns": passed_turns,
        "issue_turns": issue_turns,
        "average_score": round(sum(scores) / checked_turns) if checked_turns else 0,
        "pass_rate": round(passed_turns * 100 / checked_turns) if checked_turns else 0,
        "current_pass_streak": current_pass_streak,
        "total_issues": total_issues,
        "category_counts": category_counts,
        "score_trend": [
            {
                "turn_id": str(item.get("turn_id", "")).strip(),
                "score": score_value(item.get("score", 0)),
                "status": str(item.get("status", "")).strip(),
                "checked_at": str(item.get("checked_at", "")).strip(),
            }
            for item in reports[-10:]
        ],
    }


def merge_semantic_review(
    report: dict[str, Any],
    review: dict[str, Any],
    *,
    reviewed_at: str,
) -> dict[str, Any]:
    merged = dict(report or {})
    base_issues = [
        dict(item or {})
        for item in list(merged.get("issues", []) or [])
        if isinstance(item, dict)
        and str(item.get("source", "")).strip() != "semantic_review"
    ]
    semantic_issues = [
        dict(item or {})
        for item in list(dict(review or {}).get("issues", []) or [])
        if isinstance(item, dict)
    ]
    seen = {
        (
            str(item.get("code", "")).strip(),
            str(item.get("speaker", "")).strip(),
            str(item.get("evidence", "")).strip(),
        )
        for item in base_issues
    }
    for item in semantic_issues:
        item["source"] = "semantic_review"
        key = (
            str(item.get("code", "")).strip(),
            str(item.get("speaker", "")).strip(),
            str(item.get("evidence", "")).strip(),
        )
        if not key[0] or key in seen:
            continue
        seen.add(key)
        base_issues.append(item)

    error_count = sum(
        str(item.get("severity", "warning")).strip() == "error"
        for item in base_issues
    )
    warning_count = len(base_issues) - error_count
    merged["issues"] = base_issues
    merged["status"] = "pass" if not base_issues else "warning"
    merged["score"] = max(0, 100 - error_count * 25 - warning_count * 10)
    merged["summary"] = (
        str(dict(review or {}).get("summary", "")).strip()
        or (
            "深度复核未发现明确的语义一致性问题。"
            if not base_issues
            else f"深度复核后共发现 {len(base_issues)} 个潜在一致性问题。"
        )
    )
    coverage = dict(merged.get("coverage", {}) or {})
    coverage["semantic_review"] = True
    coverage["semantic_reviewed_at"] = reviewed_at
    merged["coverage"] = coverage
    return merged


def update_knowledge_ledger(
    current: list[dict[str, Any]] | None,
    pending_payload: dict[str, Any],
    responses: list[dict[str, Any]],
    *,
    recorded_at: str,
) -> list[dict[str, Any]]:
    """Record explicit secret disclosures and the characters present to hear them."""

    input_payload = dict(pending_payload.get("input", {}) or {})
    scene_progress = dict(
        pending_payload.get("scene_progress", input_payload.get("scene_progress", {})) or {}
    )
    holders = _normalized_names(scene_progress.get("present_participants", []))
    if not holders:
        holders = _normalized_names(input_payload.get("active_participants", []))
    input_speaker = str(input_payload.get("speaker", "")).strip()
    if input_speaker and input_speaker not in holders:
        holders.append(input_speaker)

    entries = [
        {
            "speaker": input_speaker,
            "message": str(input_payload.get("message", "")).strip(),
        },
        *[dict(item or {}) for item in responses],
    ]
    ledger = [dict(item or {}) for item in list(current or []) if isinstance(item, dict)]
    for entry in entries:
        speaker = str(entry.get("speaker", "")).strip()
        message = str(entry.get("message", "")).strip()
        if not speaker or not message:
            continue
        entry_holders = list(holders)
        if speaker not in entry_holders:
            entry_holders.append(speaker)
        for fact in _extract_secret_facts(message):
            key = re.sub(r"\s+", "", fact)
            existing = next(
                (
                    item
                    for item in ledger
                    if re.sub(r"\s+", "", str(item.get("fact", ""))) == key
                ),
                None,
            )
            if existing is not None:
                merged_holders = _normalized_names(
                    [*list(existing.get("holders", []) or []), *entry_holders]
                )
                existing["holders"] = merged_holders
                existing["updated_at"] = recorded_at
                continue
            ledger.append(
                {
                    "fact": fact,
                    "source": speaker,
                    "holders": entry_holders,
                    "created_at": recorded_at,
                    "updated_at": recorded_at,
                }
            )
    return ledger[-40:]


__all__ = [
    "build_monitor_metrics",
    "evaluate_turn_consistency",
    "merge_semantic_review",
    "update_knowledge_ledger",
    "update_monitor_state",
]
