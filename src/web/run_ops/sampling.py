from __future__ import annotations

from math import ceil
from typing import Any


def estimate_sampling_plan(
    *,
    char_count: int,
    sentence_count: int,
    character_count: int,
    max_sentences: int,
    max_chars: int,
    distill_chunk_max_chars: int,
    distill_chunk_max_sentences: int,
    relation_chunk_max_chars: int,
    relation_chunk_max_sentences: int,
) -> dict[str, Any]:
    chars = max(1, int(char_count or 1))
    sentences = max(1, int(sentence_count or 1))
    characters = max(1, int(character_count or 1))
    sampling_max_chars = _clamp(max_chars, 2_000, 200_000)
    sampling_max_sentences = _clamp(max_sentences, 20, 300)
    suggested_max_chars = _suggest_max_chars(chars)
    suggested_max_sentences = _suggest_max_sentences(sentences)
    effective_chars = max(1, min(chars, sampling_max_chars))
    effective_sentences = max(1, min(sentences, sampling_max_sentences))
    distill_chunks = _chunk_count(
        effective_chars,
        effective_sentences,
        max(1, distill_chunk_max_chars),
        max(1, distill_chunk_max_sentences),
    )
    relation_chars = min(effective_chars, 12_000)
    relation_sentences = min(effective_sentences, 80)
    relation_chunks = _chunk_count(
        relation_chars,
        relation_sentences,
        max(1, relation_chunk_max_chars),
        max(1, relation_chunk_max_sentences),
    )
    distill_calls_per_character = distill_chunks + 1 if distill_chunks > 1 else 1
    relation_calls = relation_chunks + 1 if relation_chunks > 1 else 1
    total_calls = characters * distill_calls_per_character + relation_calls
    distill_tokens_per_character = _token_budget(effective_chars, distill_chunks, mode="distill")
    relation_tokens = _token_budget(relation_chars, relation_chunks, mode="relation")
    total_tokens = characters * distill_tokens_per_character + relation_tokens
    time = _estimate_time(
        character_count=characters,
        distill_chunk_count=distill_chunks,
        relation_chunk_count=relation_chunks,
    )
    return {
        "char_count": chars,
        "sentence_count": sentences,
        "character_count": characters,
        "suggested_max_chars": suggested_max_chars,
        "suggested_max_sentences": suggested_max_sentences,
        "effective_chars": effective_chars,
        "effective_sentences": effective_sentences,
        "distill_chunk_count": distill_chunks,
        "relation_chunk_count": relation_chunks,
        "distill_calls_per_character": distill_calls_per_character,
        "relation_calls": relation_calls,
        "total_calls": total_calls,
        "token_low": _round_to_step(total_tokens * 0.82, 500),
        "token_high": _round_to_step(total_tokens * 1.18, 500),
        **time,
    }


def _suggest_max_chars(char_count: int) -> int:
    if char_count <= 50_000:
        return max(2_000, _round_to_step(char_count, 1_000))
    return min(120_000, _round_to_step(max(50_000, round(char_count * 0.38)), 5_000))


def _suggest_max_sentences(sentence_count: int) -> int:
    if sentence_count <= 120:
        return max(20, sentence_count)
    return min(300, _round_to_step(max(120, round(sentence_count * 0.32)), 10))


def _chunk_count(chars: int, sentences: int, chunk_chars: int, chunk_sentences: int) -> int:
    return max(1, ceil(chars / chunk_chars), ceil(sentences / chunk_sentences))


def _token_budget(chars: int, chunk_count: int, *, mode: str) -> int:
    char_tokens = round(chars * 1.1)
    base = 1_800 if mode == "distill" else 1_200
    if chunk_count <= 1:
        return char_tokens + base
    overhead = chunk_count * 700 + 1_100 if mode == "distill" else chunk_count * 500 + 900
    return char_tokens + base + overhead


def _estimate_time(*, character_count: int, distill_chunk_count: int, relation_chunk_count: int) -> dict[str, int]:
    workers = 6 if distill_chunk_count >= 6 else 4 if distill_chunk_count >= 4 else 2 if distill_chunk_count >= 2 else 1
    distill_low_per_character = ceil(distill_chunk_count / workers) * 22 + 28 if distill_chunk_count > 1 else 35
    distill_high_per_character = ceil(distill_chunk_count / workers) * 42 + 55 if distill_chunk_count > 1 else 70
    relation_low = ceil(relation_chunk_count / workers) * 14 + 18 if relation_chunk_count > 1 else 24
    relation_high = ceil(relation_chunk_count / workers) * 28 + 38 if relation_chunk_count > 1 else 48
    materialize_low = max(4, character_count * 3)
    materialize_high = max(8, character_count * 7)
    distill_low = character_count * distill_low_per_character + materialize_low
    distill_high = character_count * distill_high_per_character + materialize_high
    return {
        "distill_time_low_seconds": _round_to_step(distill_low, 5),
        "distill_time_high_seconds": _round_to_step(distill_high, 10),
        "relation_time_low_seconds": _round_to_step(relation_low, 5),
        "relation_time_high_seconds": _round_to_step(relation_high, 10),
        "time_low_seconds": _round_to_step(distill_low + relation_low, 5),
        "time_high_seconds": _round_to_step(distill_high + relation_high, 10),
    }


def _round_to_step(value: float, step: int) -> int:
    return max(step, int(value / step + 0.5) * step)


def _clamp(value: int, lower: int, upper: int) -> int:
    return max(lower, min(upper, int(value or lower)))
