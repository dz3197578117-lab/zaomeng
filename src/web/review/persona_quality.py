from __future__ import annotations

import hashlib
import json
import re
from typing import Any

from .persona import read_persona_review_fields
from .persona_completion import PERSONA_REVIEW_FIELD_LABELS


PERSONA_QUALITY_SCHEMA_VERSION = "persona-quality-report/v1"
PERSONA_QUALITY_EVALUATOR_VERSION = "1.1.0"
_FIELD_EVIDENCE_LIMIT = 3

_INSUFFICIENT_VALUES = {
    "不详",
    "信息不足",
    "暂无",
    "暂无资料",
    "未知",
    "资料不足",
    "证据不足",
    "待补充",
}

_LIST_FIELDS = {
    "core_traits",
    "decision_rules",
    "forbidden_behaviors",
    "key_bonds",
    "preference_like",
    "dislike_hate",
    "sentence_openers",
    "sentence_endings",
    "signature_phrases",
    "typical_lines",
}

_SHORT_SCALAR_FIELDS = {"gender"}

_HIGH_PRIORITY_FIELDS = {
    "core_identity",
    "identity_anchor",
    "soul_goal",
    "inner_conflict",
    "decision_rules",
    "core_traits",
    "key_bonds",
    "speech_style",
    "typical_lines",
    "stress_response",
}

_DIMENSION_DEFINITIONS = (
    (
        "identity",
        "身份辨识",
        20,
        (
            "core_identity",
            "story_role",
            "identity_anchor",
            "temperament_type",
            "gender",
            "age_stage",
            "appearance_feature",
            "habit_action",
            "self_cognition",
            "private_self",
            "others_impression",
        ),
    ),
    (
        "motivation",
        "动机决策",
        20,
        (
            "soul_goal",
            "hidden_desire",
            "inner_conflict",
            "thinking_style",
            "decision_rules",
            "reward_logic",
            "worldview",
            "belief_anchor",
            "moral_bottom_line",
        ),
    ),
    (
        "voice",
        "对白声音",
        25,
        (
            "speech_style",
            "cadence",
            "typical_lines",
            "signature_phrases",
            "sentence_openers",
            "sentence_endings",
        ),
    ),
    (
        "behavior",
        "行为情绪",
        20,
        (
            "core_traits",
            "key_bonds",
            "preference_like",
            "dislike_hate",
            "social_mode",
            "forbidden_behaviors",
            "restraint_threshold",
            "stress_response",
            "emotion_model",
            "anger_style",
            "joy_style",
            "grievance_style",
        ),
    ),
)

_DIMENSION_EVIDENCE_KIND_ORDER = {
    "identity": ("description", "thought", "dialogue"),
    "motivation": ("thought", "dialogue", "description"),
    "voice": ("dialogue", "thought", "description"),
    "behavior": ("description", "thought", "dialogue"),
}


def evaluate_persona_quality(
    profile: dict[str, Any],
    *,
    character: str = "",
    evidence_bundle: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Build a deterministic quality report from one materialized persona profile."""
    fields = read_persona_review_fields(profile)
    normalized_character = str(character or profile.get("name", "")).strip()
    field_results: dict[str, dict[str, Any]] = {}
    dimensions: list[dict[str, Any]] = []
    issues: list[dict[str, Any]] = []
    evidence_references = _normalize_evidence_references(evidence_bundle)

    for dimension_id, label, max_score, dimension_fields in _DIMENSION_DEFINITIONS:
        earned_units = 0.0
        for field in dimension_fields:
            result = _evaluate_field(
                field,
                fields.get(field, ""),
                dimension_id,
                evidence=_field_evidence(evidence_references, dimension_id),
            )
            field_results[field] = result
            earned_units += float(result["readiness"])
            if result["status"] != "ready":
                issues.append(_field_issue(result))
        score = round(max_score * earned_units / len(dimension_fields))
        dimensions.append(
            {
                "id": dimension_id,
                "label": label,
                "score": score,
                "max_score": max_score,
                "field_count": len(dimension_fields),
                "ready_field_count": sum(field_results[field]["status"] == "ready" for field in dimension_fields),
            }
        )

    evidence_dimension, evidence_issues, evidence_metrics = _evaluate_evidence(
        profile,
        evidence_references=evidence_references,
    )
    dimensions.append(evidence_dimension)
    issues.extend(evidence_issues)
    issues.sort(key=_issue_sort_key)

    score = sum(int(item["score"]) for item in dimensions)
    grade, verdict = _quality_grade(score)
    normalized_input = {
        "character": normalized_character,
        "fields": {field: fields.get(field, "") for field in sorted(field_results)},
        "evidence": evidence_metrics,
    }
    fingerprint = hashlib.sha256(
        json.dumps(normalized_input, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()

    return {
        "schema_version": PERSONA_QUALITY_SCHEMA_VERSION,
        "evaluator_version": PERSONA_QUALITY_EVALUATOR_VERSION,
        "character": normalized_character,
        "input_fingerprint": f"sha256:{fingerprint}",
        "score": score,
        "max_score": 100,
        "grade": grade,
        "verdict": verdict,
        "dimensions": dimensions,
        "evidence": evidence_metrics,
        "metrics": {
            "evaluated_field_count": len(field_results),
            "ready_field_count": sum(item["status"] == "ready" for item in field_results.values()),
            "thin_field_count": sum(item["status"] == "thin" for item in field_results.values()),
            "missing_field_count": sum(item["status"] in {"missing", "insufficient"} for item in field_results.values()),
            "issue_count": len(issues),
            "evidence": evidence_metrics,
        },
        "field_results": [field_results[field] for field in sorted(field_results)],
        "issues": issues,
    }


def _evaluate_field(
    field: str,
    value: Any,
    dimension: str,
    *,
    evidence: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    text = str(value or "").strip()
    normalized = re.sub(r"\s+", "", text)
    if not normalized:
        status = "missing"
        readiness = 0.0
    elif normalized in _INSUFFICIENT_VALUES:
        status = "insufficient"
        readiness = 0.0
    elif (field not in _SHORT_SCALAR_FIELDS and len(normalized) < 5) or (field in _LIST_FIELDS and len(_split_items(text)) < 2):
        status = "thin"
        readiness = 0.5
    else:
        status = "ready"
        readiness = 1.0
    return {
        "field": field,
        "label": PERSONA_REVIEW_FIELD_LABELS.get(field, field),
        "dimension": dimension,
        "status": status,
        "readiness": readiness,
        "value_length": len(normalized),
        "evidence": list(evidence or []),
    }


def _field_issue(result: dict[str, Any]) -> dict[str, Any]:
    field = str(result["field"])
    label = str(result["label"])
    status = str(result["status"])
    high_priority = field in _HIGH_PRIORITY_FIELDS
    if status in {"missing", "insufficient"}:
        severity = "high" if high_priority else "medium"
        message = f"{label}尚未形成可用结论。"
        suggestion = f"补充{label}；无法从正文确认时保持为空，并增加对应原文。"
    else:
        severity = "medium" if high_priority else "low"
        message = f"{label}内容过薄，难以稳定约束人物表现。"
        suggestion = f"把{label}补成更具体、可观察的描述。"
    return {
        "code": f"field.{field}.{status}",
        "severity": severity,
        "dimension": result["dimension"],
        "fields": [field],
        "message": message,
        "evidence": list(result.get("evidence", []) or []),
        "suggestion": suggestion,
    }


def _evaluate_evidence(
    profile: dict[str, Any],
    *,
    evidence_references: list[dict[str, Any]] | None = None,
) -> tuple[dict[str, Any], list[dict[str, Any]], dict[str, Any]]:
    nested = profile.get("evidence", {})
    evidence = nested if isinstance(nested, dict) else {}
    description_count = _non_negative_int(profile.get("description_count", evidence.get("description_count", 0)))
    dialogue_count = _non_negative_int(profile.get("dialogue_count", evidence.get("dialogue_count", 0)))
    thought_count = _non_negative_int(profile.get("thought_count", evidence.get("thought_count", 0)))
    chunk_count = _non_negative_int(profile.get("chunk_count", evidence.get("chunk_count", 0)))
    references = list(evidence_references or [])
    evidence_source = str(profile.get("evidence_source", "")).strip() or _reference_source_summary(references)

    score = 0
    score += round(3 * min(description_count / 3, 1.0))
    score += round(4 * min(dialogue_count / 3, 1.0))
    score += round(3 * min(thought_count / 2, 1.0))
    score += round(2 * min(chunk_count / 2, 1.0))
    score += 3 if evidence_source else 0
    metrics = {
        "description_count": description_count,
        "dialogue_count": dialogue_count,
        "thought_count": thought_count,
        "chunk_count": chunk_count,
        "evidence_source": evidence_source,
    }
    if references:
        metrics["reference_count"] = len(references)
        metrics["references"] = references
    issues: list[dict[str, Any]] = []
    if description_count + dialogue_count + thought_count == 0:
        issues.append(
            {
                "code": "evidence.none",
                "severity": "high",
                "dimension": "evidence",
                "fields": [],
                "message": "档案没有可计数的正文证据。",
                "evidence": references[:_FIELD_EVIDENCE_LIMIT],
                "suggestion": "重新抽取包含人物描写、对白或心理活动的正文片段。",
            }
        )
    elif dialogue_count == 0:
        issues.append(
            {
                "code": "evidence.dialogue_missing",
                "severity": "high",
                "dimension": "evidence",
                "fields": ["speech_style", "typical_lines"],
                "message": "档案缺少对白证据，声音特征无法可靠验证。",
                "evidence": [item for item in references if item.get("kind") != "dialogue"][:_FIELD_EVIDENCE_LIMIT],
                "suggestion": "补充至少三条能体现口吻和节奏的原文对白。",
            }
        )
    if not evidence_source:
        issues.append(
            {
                "code": "evidence.source_missing",
                "severity": "medium",
                "dimension": "evidence",
                "fields": [],
                "message": "证据来源没有记录。",
                "evidence": references[:_FIELD_EVIDENCE_LIMIT],
                "suggestion": "保存片段阶段、分块或章节定位，便于回到原文复核。",
            }
        )
    return (
        {
            "id": "evidence",
            "label": "证据支撑",
            "score": score,
            "max_score": 15,
            "field_count": 0,
            "ready_field_count": 0,
        },
        issues,
        metrics,
    )


def _quality_grade(score: int) -> tuple[str, str]:
    if score >= 80:
        return "ready", "可进入对话回归"
    if score >= 60:
        return "usable", "基本可用，建议先修高优先级问题"
    if score >= 40:
        return "needs_work", "需要补强后再用于稳定演绎"
    return "insufficient", "信息不足，不建议直接进入演绎"


def _split_items(value: str) -> list[str]:
    return [item.strip() for item in re.split(r"\s*[；;]\s*", value) if item.strip()]


def _normalize_evidence_references(evidence_bundle: dict[str, Any] | None) -> list[dict[str, Any]]:
    if not isinstance(evidence_bundle, dict):
        return []
    normalized: list[dict[str, Any]] = []
    seen_quotes: set[str] = set()
    for index, item in enumerate(list(evidence_bundle.get("references", []) or []), start=1):
        if not isinstance(item, dict):
            continue
        quote = re.sub(r"\s+", " ", str(item.get("quote", ""))).strip()
        if not quote or quote in seen_quotes:
            continue
        seen_quotes.add(quote)
        kind = str(item.get("kind", "description")).strip()
        if kind not in {"description", "dialogue", "thought"}:
            kind = "description"
        normalized.append(
            {
                "id": str(item.get("id", "")).strip() or f"evidence-{index}",
                "stage": str(item.get("stage", "excerpt")).strip() or "excerpt",
                "stage_label": str(item.get("stage_label", "正文片段")).strip() or "正文片段",
                "kind": kind,
                "kind_label": str(item.get("kind_label", "原文")).strip() or "原文",
                "quote": quote,
                "source": str(item.get("source", "excerpt:excerpt")).strip() or "excerpt:excerpt",
                "mentions_character": bool(item.get("mentions_character", False)),
            }
        )
    return normalized


def _field_evidence(references: list[dict[str, Any]], dimension: str) -> list[dict[str, Any]]:
    kind_order = _DIMENSION_EVIDENCE_KIND_ORDER.get(dimension, ("description", "dialogue", "thought"))
    kind_rank = {kind: index for index, kind in enumerate(kind_order)}
    ranked = sorted(
        enumerate(references),
        key=lambda item: (
            kind_rank.get(str(item[1].get("kind", "")), len(kind_rank)),
            0 if item[1].get("mentions_character") else 1,
            item[0],
        ),
    )
    return [
        {**reference, "match_basis": f"dimension:{dimension}"}
        for _, reference in ranked[:_FIELD_EVIDENCE_LIMIT]
    ]


def _reference_source_summary(references: list[dict[str, Any]]) -> str:
    sources: list[str] = []
    for reference in references:
        source = str(reference.get("source", "")).strip()
        if source and source not in sources:
            sources.append(source)
    return "；".join(sources)


def _non_negative_int(value: Any) -> int:
    try:
        return max(0, int(value or 0))
    except (TypeError, ValueError):
        return 0


def _issue_sort_key(issue: dict[str, Any]) -> tuple[int, str]:
    severity_order = {"high": 0, "medium": 1, "low": 2}
    return severity_order.get(str(issue.get("severity", "")), 3), str(issue.get("code", ""))


__all__ = [
    "PERSONA_QUALITY_EVALUATOR_VERSION",
    "PERSONA_QUALITY_SCHEMA_VERSION",
    "evaluate_persona_quality",
]
