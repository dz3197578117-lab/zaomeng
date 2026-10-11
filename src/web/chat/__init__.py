from __future__ import annotations

from importlib import import_module
from typing import Any

_ENTRYPOINT_EXPORTS = {
    "associate_dialogue_turn_payload",
    "continue_dialogue_scene_opening_payload",
    "create_dialogue_session_payload",
    "reply_dialogue_turn_payload",
    "suggest_dialogue_turn_payload",
}
_HELPER_EXPORTS = {
    "build_dialogue_association_llm_messages",
    "build_dialogue_consistency_review_messages",
    "build_dialogue_director_llm_messages",
    "build_dialogue_llm_messages",
    "build_dialogue_opening_message",
    "build_dialogue_relation_state_messages",
    "build_dialogue_scene_progress_messages",
    "build_dialogue_suggestion_llm_messages",
    "compact_dialogue_suggestion_payload",
    "friendly_dialogue_llm_error",
    "generate_dialogue_associations",
    "generate_dialogue_responses",
    "generate_dialogue_suggestion",
    "parse_dialogue_associations",
    "parse_dialogue_consistency_review",
    "parse_dialogue_director_options",
    "parse_dialogue_responses",
    "parse_dialogue_relation_state",
    "parse_dialogue_scene_progress",
    "parse_dialogue_suggestion",
    "should_retry_suggestion_with_compact_payload",
}
_RUNTIME_EXPORTS = {
    "generate_dialogue_associations_for_run",
    "generate_dialogue_responses_for_run",
    "generate_dialogue_suggestion_for_run",
    "load_pending_turn_payload",
}

__all__ = [
    "DialogueService",
    "associate_dialogue_turn_payload",
    "build_dialogue_association_llm_messages",
    "build_dialogue_consistency_review_messages",
    "build_dialogue_director_llm_messages",
    "build_dialogue_llm_messages",
    "build_dialogue_opening_message",
    "build_dialogue_relation_state_messages",
    "build_dialogue_scene_progress_messages",
    "build_dialogue_suggestion_llm_messages",
    "compact_dialogue_suggestion_payload",
    "continue_dialogue_scene_opening_payload",
    "create_dialogue_session_payload",
    "friendly_dialogue_llm_error",
    "generate_dialogue_associations",
    "generate_dialogue_associations_for_run",
    "generate_dialogue_responses",
    "generate_dialogue_responses_for_run",
    "generate_dialogue_suggestion",
    "generate_dialogue_suggestion_for_run",
    "load_pending_turn_payload",
    "parse_dialogue_associations",
    "parse_dialogue_consistency_review",
    "parse_dialogue_director_options",
    "parse_dialogue_responses",
    "parse_dialogue_relation_state",
    "parse_dialogue_scene_progress",
    "parse_dialogue_suggestion",
    "reply_dialogue_turn_payload",
    "should_retry_suggestion_with_compact_payload",
    "suggest_dialogue_turn_payload",
]


def __getattr__(name: str) -> Any:
    if name == "DialogueService":
        module = import_module(".service", __name__)
        return module.DialogueService
    if name in _ENTRYPOINT_EXPORTS:
        entrypoints = import_module(".entrypoints", __name__)
        return getattr(entrypoints, name)
    if name in _HELPER_EXPORTS:
        helpers = import_module(".helpers", __name__)
        return getattr(helpers, name)
    if name in _RUNTIME_EXPORTS:
        runtime = import_module(".runtime", __name__)
        return getattr(runtime, name)
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
