from __future__ import annotations

import re
from typing import Any


_EMPTY_PERSONA_MARKERS = {
    "证据不足",
    "资料不足",
    "信息不足",
    "依据不足",
    "未知",
    "不详",
    "未详",
    "暂缺",
    "待补充",
    "暂无资料",
    "暂无信息",
    "无法判断",
    "无法确定",
    "不能确定",
}
_ELLIPSIS_ONLY_RE = re.compile(r"^(?:\.{2,}|…+|⋯+|。{2,}|．{2,})$")


def is_empty_persona_marker(value: Any) -> bool:
    """Return whether a generated persona value is only an unknown placeholder."""

    text = re.sub(r"\s+", "", str(value or "").strip())
    text = text.strip("`'\"“”‘’")
    return text in _EMPTY_PERSONA_MARKERS or bool(_ELLIPSIS_ONLY_RE.fullmatch(text))


def empty_persona_marker(value: Any) -> str:
    text = str(value or "").strip()
    return "" if is_empty_persona_marker(text) else text


def sanitize_persona_value(value: Any) -> Any:
    """Recursively turn placeholder-only persona values into real empty values."""

    if isinstance(value, str):
        return empty_persona_marker(value)
    if isinstance(value, list):
        cleaned = [sanitize_persona_value(item) for item in value]
        return [item for item in cleaned if item not in ("", None, [], {})]
    if isinstance(value, tuple):
        cleaned = [sanitize_persona_value(item) for item in value]
        return tuple(item for item in cleaned if item not in ("", None, [], {}))
    if isinstance(value, dict):
        return {key: sanitize_persona_value(item) for key, item in value.items()}
    return value
