from __future__ import annotations

import json
import unittest
from pathlib import Path
from types import SimpleNamespace

from src.web.chat.helpers import (
    DIALOGUE_SUGGESTION_COMPACT_PROMPT_CHAR_THRESHOLD,
    build_dialogue_association_llm_messages,
    build_dialogue_llm_messages,
    build_dialogue_suggestion_llm_messages,
    generate_dialogue_associations,
    generate_dialogue_suggestion,
    parse_dialogue_associations,
    parse_dialogue_suggestion,
)
from src.web.chat.runtime import (
    generate_dialogue_associations_for_run,
    generate_dialogue_suggestion_for_run,
)
from src.web.api.routes.dialogue import reply_dialogue_turn
from src.web.api.schemas import PrepareDialogueTurnRequest


class DialogueLatencyTests(unittest.TestCase):
    def test_main_reply_prompt_uses_compact_json_without_losing_structure(self):
        messages = build_dialogue_llm_messages(
            {
                "mode": "insert",
                "input": {
                    "speaker": "阿眠",
                    "message": "继续说。",
                    "participants": ["林黛玉"],
                    "active_participants": ["林黛玉"],
                },
                "persona_contexts": [{"name": "林黛玉"}],
                "host_action": {
                    "expected_output": [
                        {"speaker": "林黛玉", "message": "回复内容"}
                    ]
                },
            }
        )

        user_payload = json.loads(messages[-1]["content"])
        self.assertEqual(user_payload["message"], "继续说。")
        self.assertNotIn("\n", messages[-1]["content"])
        self.assertNotIn("\n  ", messages[0]["content"])

    def test_web_reply_route_skips_noncritical_refinement_llm_calls(self):
        captured: dict[str, object] = {}

        class FakeRunService:
            def reply_dialogue_turn(self, run_id, **kwargs):
                captured["run_id"] = run_id
                captured.update(kwargs)
                return {"status": "ready"}

        result = reply_dialogue_turn(
            "run-1",
            "session-1",
            PrepareDialogueTurnRequest(message="继续说。"),
            FakeRunService(),
        )

        self.assertEqual(result, {"status": "ready"})
        self.assertIs(captured["fast_response"], True)

    def test_association_prompt_keeps_salient_context_but_bounds_expansion(self):
        payload = {
            "mode": "insert",
            "input": {
                "speaker": "阿眠",
                "participants": ["林黛玉", "贾宝玉"],
            },
            "latest_exchange": {
                "user_turn": {"speaker": "阿眠", "message": "你刚才为什么停住？"},
                "replies": [
                    {
                        "speaker": "林黛玉",
                        "message": "当年的事，我并不是全然忘了。",
                    }
                ],
                "present_participants": ["林黛玉", "贾宝玉"],
                "offstage_participants": [],
            },
            "history": [
                {
                    "speaker": "林黛玉",
                    "role": "assistant",
                    "message": f"第 {index} 轮" + "旧对话" * 100,
                    "unused": "不应发送" * 100,
                }
                for index in range(8)
            ],
            "scene_card": {
                "title": "潇湘馆夜话",
                "location": "潇湘馆",
                "time_hint": "深夜",
                "public_goal": "问清旧事",
                "hidden_tension": "彼此都怕先说破",
                "unused": "低价值场景展开" * 200,
            },
            "scene_progress": {
                "present_participants": ["林黛玉", "贾宝玉"],
                "world_tension_summary": "旧事将要说破",
                "unused": "低价值进度展开" * 200,
            },
            "memory_context": {
                "session_summary": {
                    "current_location": "潇湘馆",
                    "pending_commitments": "答应把旧事说清",
                    "unused": "低价值摘要" * 200,
                },
                "archived_summary": {
                    "summary": "两人曾因误会疏远",
                    "key_points": ["宝玉尚未解释旧信"],
                },
                "retrieved_memories": [
                    {"text": "黛玉仍留着旧信", "speaker": "林黛玉"}
                ],
                "controlled_memories": [
                    {
                        "text": "阿眠不知道旧信的全部来历" + "。" * 300,
                        "category": "knowledge",
                        "pinned": True,
                    }
                ],
            },
            "user_persona": {
                "mode": "insert",
                "speaker": "阿眠",
                "source": "self_insert_profile",
                "must_follow": "保持克制，不替别人下结论。",
                "profile": {
                    "display_name": "阿眠",
                    "scene_identity": "误入园中的来客",
                    "interaction_style": "先听后问",
                    "speech_style": "说话轻但问题很准",
                    "unused": "完整角色卡低价值字段" * 200,
                },
            },
            "persona_contexts": [
                {
                    "name": "林黛玉",
                    "preview": {
                        "display_name": "林黛玉",
                        "core_identity": "敏感自持的少女",
                        "speech_style": "轻冷带刺",
                    },
                    "profile": {
                        "core_identity": "林府孤女",
                        "story_role": "情感核心",
                        "speech_style": "轻冷带刺",
                        "temperament_type": "敏感自持",
                        "stress_response": "越难受越把话说轻",
                        "unused": "完整人物档案低价值字段" * 200,
                    },
                    "session_snapshot": {
                        "mood": "迟疑",
                        "focus": "旧信",
                    },
                }
            ],
            "relation_context": {"relations_excerpt": "彼此牵挂。" * 400},
            "instructions": {
                "option_count": 3,
                "generation_goal": "紧接最新回复给出三个不同方向。",
            },
            "host_action": {
                "expected_output": {"options": []},
                "output_rule": "返回三个有锚点的方向。",
            },
        }

        messages = build_dialogue_association_llm_messages(payload)
        user_payload = json.loads(messages[1]["content"])

        self.assertEqual(
            user_payload["latest_exchange"]["replies"][0]["message"],
            "当年的事，我并不是全然忘了。",
        )
        self.assertEqual(user_payload["participants"], ["林黛玉", "贾宝玉"])
        self.assertEqual(user_payload["scene_card"]["public_goal"], "问清旧事")
        self.assertEqual(user_payload["scene_card"]["time_hint"], "深夜")
        self.assertEqual(
            user_payload["persona_contexts"][0]["session_snapshot"]["focus"],
            "旧信",
        )
        self.assertEqual(len(user_payload["recent_completed_history"]), 4)
        self.assertTrue(
            all(
                len(item["message"]) <= 160
                for item in user_payload["recent_completed_history"]
            )
        )
        self.assertNotIn("unused", user_payload["scene_card"])
        self.assertNotIn("unused", user_payload["scene_progress"])
        self.assertNotIn("unused", user_payload["persona_contexts"][0]["profile"])
        self.assertLessEqual(len(user_payload["relation_excerpt"]), 800)
        self.assertEqual(
            user_payload["memory_anchors"]["session_summary"][
                "pending_commitments"
            ],
            "答应把旧事说清",
        )
        self.assertTrue(
            user_payload["memory_anchors"]["controlled_memories"][0]["pinned"]
        )
        self.assertLessEqual(
            len(
                user_payload["memory_anchors"]["controlled_memories"][0]["text"]
            ),
            180,
        )
        self.assertIn(
            "suggestion",
            user_payload["response_shape"]["options"][0],
        )

    def test_association_parser_keeps_optional_prefetched_suggestions(self):
        options = parse_dialogue_associations(
            json.dumps(
                {
                    "options": [
                        {
                            "label": "Ask again",
                            "direction": "Ask what happened next",
                            "suggestion": "Tell me what happened next.",
                        },
                        {
                            "label": "Leave now",
                            "direction": "End the exchange and leave",
                            "draft": "Let us leave now.",
                        },
                        {
                            "label": "Stay quiet",
                            "direction": "Wait for the other person to continue",
                        },
                    ]
                }
            )
        )

        self.assertEqual(options[0]["suggestion"], "Tell me what happened next.")
        self.assertEqual(options[1]["suggestion"], "Let us leave now.")
        self.assertNotIn("suggestion", options[2])

    def test_suggestion_generation_caps_first_attempt_and_expands_only_on_retry(self):
        limits: list[int] = []
        replies = iter(
            [
                {"content": "partial", "finish_reason": "length"},
                {"content": "A complete line.", "finish_reason": "stop"},
            ]
        )

        def complete(_messages, _temperature, max_tokens):
            limits.append(max_tokens)
            return next(replies)

        suggestion = generate_dialogue_suggestion(
            payload={},
            temperature=0.45,
            max_tokens=4096,
            chat_completion=complete,
            build_messages=lambda _payload, retry: [
                {"role": "user", "content": f"short:{retry}"}
            ],
            parse_suggestion=parse_dialogue_suggestion,
        )

        self.assertEqual(suggestion, "A complete line.")
        self.assertEqual(limits, [512, 1024])

    def test_suggestion_runtime_passes_a_512_token_first_attempt_cap(self):
        captured: dict[str, int] = {}
        config = {"llm.max_tokens": 4096, "llm.temperature": 0.45}
        parts = SimpleNamespace(
            llm=SimpleNamespace(chat_completion=lambda *args, **kwargs: {})
        )

        def capture_limit(**kwargs):
            captured["max_tokens"] = kwargs["max_tokens"]
            return "ready"

        result = generate_dialogue_suggestion_for_run(
            run_dir=Path("."),
            payload={},
            build_runtime_config_for_run=lambda **_kwargs: config,
            build_runtime_parts=lambda _config: parts,
            generate_dialogue_suggestion=capture_limit,
            build_dialogue_suggestion_llm_messages=lambda _payload, _retry: [],
            parse_dialogue_suggestion=lambda content: content,
        )

        self.assertEqual(result, "ready")
        self.assertEqual(captured["max_tokens"], 512)

    def test_long_suggestion_prompt_uses_compact_payload_on_first_attempt(self):
        payload = {
            "mode": "insert",
            "input": {"speaker": "Me", "participants": ["A"]},
            "memory_context": {
                "controlled_memories": [
                    {
                        "text": "memory " + ("x" * 2_000),
                        "category": "story",
                        "pinned": index == 0,
                    }
                    for index in range(20)
                ]
            },
            "user_persona": {"profile": {"display_name": "Me"}},
        }
        full_messages = build_dialogue_suggestion_llm_messages(payload)
        full_chars = sum(len(item["content"]) for item in full_messages)
        sent_prompt_sizes: list[int] = []

        def complete(messages, _temperature, _max_tokens):
            sent_prompt_sizes.append(sum(len(item["content"]) for item in messages))
            return {"content": "A direct line.", "finish_reason": "stop"}

        suggestion = generate_dialogue_suggestion(
            payload=payload,
            temperature=0.45,
            max_tokens=512,
            chat_completion=complete,
            build_messages=lambda current, retry: build_dialogue_suggestion_llm_messages(
                current,
                retry_on_empty=retry,
            ),
            parse_suggestion=parse_dialogue_suggestion,
        )

        self.assertGreater(
            full_chars,
            DIALOGUE_SUGGESTION_COMPACT_PROMPT_CHAR_THRESHOLD,
        )
        self.assertEqual(suggestion, "A direct line.")
        self.assertLess(sent_prompt_sizes[0], full_chars)

    def test_short_suggestion_prompt_is_unchanged(self):
        payload = {
            "mode": "insert",
            "input": {"speaker": "Me", "participants": ["A"]},
            "history": [{"speaker": "A", "message": "Hello."}],
            "user_persona": {"profile": {"display_name": "Me"}},
        }
        expected = build_dialogue_suggestion_llm_messages(payload)
        sent_messages: list[list[dict[str, str]]] = []

        def complete(messages, _temperature, _max_tokens):
            sent_messages.append(messages)
            return {"content": "Hello back.", "finish_reason": "stop"}

        generate_dialogue_suggestion(
            payload=payload,
            temperature=0.45,
            max_tokens=512,
            chat_completion=complete,
            build_messages=lambda current, retry: build_dialogue_suggestion_llm_messages(
                current,
                retry_on_empty=retry,
            ),
            parse_suggestion=parse_dialogue_suggestion,
        )

        self.assertEqual(sent_messages[0], expected)

    def test_association_runtime_caps_only_the_first_structured_attempt(self):
        captured: dict[str, int] = {}
        config = {"llm.max_tokens": 4096, "llm.temperature": 0.5}
        parts = SimpleNamespace(
            llm=SimpleNamespace(chat_completion=lambda *args, **kwargs: {})
        )

        def capture_limit(**kwargs):
            captured["max_tokens"] = kwargs["max_tokens"]
            return []

        result = generate_dialogue_associations_for_run(
            run_dir=Path("."),
            payload={},
            build_runtime_config_for_run=lambda **_kwargs: config,
            build_runtime_parts=lambda _config: parts,
            generate_dialogue_associations=capture_limit,
            build_dialogue_association_llm_messages=lambda _payload, _retry: [],
            parse_dialogue_associations=lambda _content: [],
        )

        self.assertEqual(result, [])
        self.assertEqual(captured["max_tokens"], 768)

    def test_association_generation_retries_with_larger_budget_when_truncated(self):
        limits: list[int] = []
        replies = iter(
            [
                {"content": "{", "finish_reason": "length"},
                {
                    "content": json.dumps(
                        {
                            "options": [
                                {
                                    "label": "追问旧事",
                                    "direction": "顺着黛玉提到的旧事继续追问",
                                    "anchor_speaker": "林黛玉",
                                    "anchor_quote": "当年的事",
                                },
                                {
                                    "label": "先接住情绪",
                                    "direction": "回应黛玉并未全忘的迟疑",
                                    "anchor_speaker": "林黛玉",
                                    "anchor_quote": "并不是全然忘了",
                                },
                                {
                                    "label": "请她慢慢说",
                                    "direction": "给黛玉留出把旧事说完整的余地",
                                    "anchor_speaker": "林黛玉",
                                    "anchor_quote": "我并不是全然忘了",
                                },
                            ]
                        },
                        ensure_ascii=False,
                    ),
                    "finish_reason": "stop",
                },
            ]
        )

        def complete(_messages, _temperature, max_tokens):
            limits.append(max_tokens)
            return next(replies)

        options = generate_dialogue_associations(
            payload={
                "instructions": {"option_count": 3},
                "latest_exchange": {
                    "replies": [
                        {
                            "speaker": "林黛玉",
                            "message": "当年的事，我并不是全然忘了。",
                        }
                    ]
                },
            },
            temperature=0.5,
            max_tokens=768,
            chat_completion=complete,
            build_messages=lambda _payload, retry: [
                {"role": "user", "content": str(retry)}
            ],
            parse_associations=parse_dialogue_associations,
        )

        self.assertEqual(len(options), 3)
        self.assertEqual(limits, [768, 1536])

    def test_session_render_starts_associations_before_noncritical_refreshes(self):
        source = (
            Path(__file__).resolve().parents[1]
            / "src"
            / "web"
            / "static"
            / "js"
            / "dialogue.js"
        ).read_text(encoding="utf-8")
        body = source.split("async function renderDialogueSession(session) {", 1)[1]
        body = body.split("\n}\n\nfunction sessionListItemKey", 1)[0]

        association_index = body.index("window.maybeRequestDialogueAssociations(session);")
        recent_index = body.index("void loadRecentSessions()")
        scene_index = body.index("Promise.resolve(associationRequest)")
        self.assertLess(association_index, recent_index)
        self.assertLess(association_index, scene_index)
        self.assertNotIn("await loadRecentSessions()", body)
        self.assertNotIn("await maybeAutoRecommendNextScene(session)", body)
        self.assertIn(
            "then(() => maybeAutoRecommendNextScene(session))",
            body,
        )

    def test_slow_reply_feedback_appears_without_an_eight_second_silent_wait(self):
        source = (
            Path(__file__).resolve().parents[1]
            / "src"
            / "web"
            / "static"
            / "js"
            / "main.js"
        ).read_text(encoding="utf-8")

        self.assertIn("const DIALOGUE_RETRY_FEEDBACK_DELAY_MS = 4000;", source)

    def test_prefetched_association_suggestion_skips_the_suggest_request(self):
        source = (
            Path(__file__).resolve().parents[1]
            / "src"
            / "web"
            / "static"
            / "js"
            / "main.js"
        ).read_text(encoding="utf-8")
        choice_body = source.split(
            "async function handleDialogueAssociationChoice(option) {", 1
        )[1].split("\n}\n\nasync function handleSendTurn", 1)[0]

        self.assertIn(
            'const prefetchedSuggestion = String(option?.suggestion || option?.draft || "").trim();',
            choice_body,
        )
        self.assertIn("let suggestion = prefetchedSuggestion;", choice_body)
        self.assertIn("if (!suggestion) {", choice_body)
        self.assertLess(
            choice_body.index("if (!suggestion) {"),
            choice_body.index("/suggest`"),
        )


if __name__ == "__main__":
    unittest.main()
