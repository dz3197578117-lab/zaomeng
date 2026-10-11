from __future__ import annotations

import importlib
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from src.web.artifacts import ingest
from src.web.chat import relation_excerpt


persona_bundle = importlib.import_module(ingest.load_profile_source.__module__)


class PersonaSourceCacheTests(unittest.TestCase):
    def setUp(self) -> None:
        with persona_bundle._PROFILE_SOURCE_CACHE_LOCK:
            persona_bundle._PROFILE_SOURCE_CACHE.clear()
        with relation_excerpt._RELATION_TEXT_CACHE_LOCK:
            relation_excerpt._RELATION_TEXT_CACHE.clear()

    def test_profile_cache_reuses_parse_and_returns_deep_copies(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "PROFILE.md"
            source.write_text(
                "- name: Alice\n- signature_phrases: hello;goodbye\n",
                encoding="utf-8",
            )

            with patch.object(
                persona_bundle,
                "_read_profile_source",
                wraps=persona_bundle._read_profile_source,
            ) as reader:
                first = persona_bundle.load_profile_source(source)
                first["speech_habits"]["signature_phrases"].append("mutated")
                second = persona_bundle.load_profile_source(
                    source.parent / "." / source.name
                )

            self.assertEqual(reader.call_count, 1)
            self.assertEqual(
                second["speech_habits"]["signature_phrases"], ["hello", "goodbye"]
            )

    def test_profile_cache_invalidates_on_size_or_mtime_change(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "PROFILE.md"
            source.write_text("- name: Alice\n- speech_style: calm\n", encoding="utf-8")
            initial_stat = source.stat()

            with patch.object(
                persona_bundle,
                "_read_profile_source",
                wraps=persona_bundle._read_profile_source,
            ) as reader:
                first = persona_bundle.load_profile_source(source)
                source.write_text(
                    "- name: Alice\n- speech_style: very calm\n", encoding="utf-8"
                )
                os.utime(
                    source, ns=(initial_stat.st_atime_ns, initial_stat.st_mtime_ns)
                )
                second = persona_bundle.load_profile_source(source)

                second_stat = source.stat()
                source.write_text(
                    "- name: Alice\n- speech_style: very loud\n", encoding="utf-8"
                )
                os.utime(
                    source,
                    ns=(
                        second_stat.st_atime_ns,
                        second_stat.st_mtime_ns + 1_000_000_000,
                    ),
                )
                third = persona_bundle.load_profile_source(source)

            self.assertEqual(reader.call_count, 3)
            self.assertEqual(first["speech_style"], "calm")
            self.assertEqual(second["speech_style"], "very calm")
            self.assertEqual(third["speech_style"], "very loud")

    def test_profile_cache_is_bounded_and_tracks_recent_use(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(
            persona_bundle, "_PROFILE_SOURCE_CACHE_MAX_SIZE", 2
        ):
            sources = []
            for index in range(3):
                source = Path(tmp) / f"PROFILE-{index}.md"
                source.write_text(f"- name: Character{index}\n", encoding="utf-8")
                sources.append(source.resolve())

            persona_bundle.load_profile_source(sources[0])
            persona_bundle.load_profile_source(sources[1])
            persona_bundle.load_profile_source(sources[0])
            persona_bundle.load_profile_source(sources[2])

            self.assertEqual(len(persona_bundle._PROFILE_SOURCE_CACHE), 2)
            self.assertIn(sources[0], persona_bundle._PROFILE_SOURCE_CACHE)
            self.assertIn(sources[2], persona_bundle._PROFILE_SOURCE_CACHE)
            self.assertNotIn(sources[1], persona_bundle._PROFILE_SOURCE_CACHE)


class RelationTextCacheTests(unittest.TestCase):
    def setUp(self) -> None:
        with relation_excerpt._RELATION_TEXT_CACHE_LOCK:
            relation_excerpt._RELATION_TEXT_CACHE.clear()

    def test_relation_cache_reuses_full_text_for_different_excerpt_limits(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "relations.md"
            source.write_text("0123456789", encoding="utf-8")

            with patch.object(
                relation_excerpt,
                "_read_relation_text",
                wraps=relation_excerpt._read_relation_text,
            ) as reader:
                short = relation_excerpt.load_text_excerpt(str(source), limit=4)
                long = relation_excerpt.load_text_excerpt(str(source), limit=8)

            self.assertEqual(reader.call_count, 1)
            self.assertEqual(short, "0123")
            self.assertEqual(long, "01234567")

    def test_relation_cache_invalidates_and_stays_bounded(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(
            relation_excerpt, "_RELATION_TEXT_CACHE_MAX_SIZE", 2
        ):
            sources = []
            for index in range(3):
                source = Path(tmp) / f"relations-{index}.md"
                source.write_text(f"relation {index}", encoding="utf-8")
                sources.append(source)

            relation_excerpt.load_text_excerpt(str(sources[0]), limit=20)
            relation_excerpt.load_text_excerpt(str(sources[1]), limit=20)
            relation_excerpt.load_text_excerpt(str(sources[0]), limit=20)
            relation_excerpt.load_text_excerpt(str(sources[2]), limit=20)

            self.assertEqual(len(relation_excerpt._RELATION_TEXT_CACHE), 2)
            self.assertNotIn(
                sources[1].resolve(), relation_excerpt._RELATION_TEXT_CACHE
            )

            previous = sources[0].stat()
            sources[0].write_text("relation zero changed", encoding="utf-8")
            os.utime(sources[0], ns=(previous.st_atime_ns, previous.st_mtime_ns))
            refreshed = relation_excerpt.load_text_excerpt(str(sources[0]), limit=30)

            self.assertEqual(refreshed, "relation zero changed")


if __name__ == "__main__":
    unittest.main()
