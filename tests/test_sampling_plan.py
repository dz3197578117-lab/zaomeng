from src.web.run_ops.sampling import estimate_sampling_plan


def test_sampling_plan_suggests_full_short_text_and_single_calls():
    plan = estimate_sampling_plan(
        char_count=12_400,
        sentence_count=88,
        character_count=2,
        max_sentences=120,
        max_chars=50_000,
        distill_chunk_max_chars=9_000,
        distill_chunk_max_sentences=70,
        relation_chunk_max_chars=4_800,
        relation_chunk_max_sentences=36,
    )

    assert plan["suggested_max_chars"] == 12_000
    assert plan["suggested_max_sentences"] == 88
    assert plan["distill_chunk_count"] == 2
    assert plan["distill_calls_per_character"] == 3
    assert plan["total_calls"] == 10
    assert plan["token_low"] < plan["token_high"]


def test_sampling_plan_clamps_inputs_and_scales_long_text():
    plan = estimate_sampling_plan(
        char_count=900_000,
        sentence_count=9_000,
        character_count=3,
        max_sentences=300,
        max_chars=200_000,
        distill_chunk_max_chars=9_000,
        distill_chunk_max_sentences=70,
        relation_chunk_max_chars=4_800,
        relation_chunk_max_sentences=36,
    )

    assert plan["suggested_max_chars"] == 120_000
    assert plan["suggested_max_sentences"] == 300
    assert plan["effective_chars"] == 200_000
    assert plan["distill_chunk_count"] > 1
    assert plan["time_high_seconds"] > plan["time_low_seconds"]
