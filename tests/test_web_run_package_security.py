from __future__ import annotations

import base64
import io
import json
import os
import stat
import tempfile
import unittest
import zipfile
from pathlib import Path
from typing import Any
from unittest.mock import Mock, patch

import src.web.run_ops.packages as run_packages


class RunPackageArchiveSecurityTests(unittest.TestCase):
    @staticmethod
    def _build_package_bytes(
        members: list[tuple[str | zipfile.ZipInfo, bytes]],
        *,
        manifest: dict[str, Any] | None = None,
        manifest_compression: int = zipfile.ZIP_DEFLATED,
    ) -> bytes:
        payload = manifest or {
            "kind": run_packages.PACKAGE_KIND,
            "schema_version": run_packages.PACKAGE_SCHEMA_VERSION,
        }
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr(
                run_packages.PACKAGE_MANIFEST_NAME,
                json.dumps(payload).encode("utf-8"),
                compress_type=manifest_compression,
            )
            for member, content in members:
                archive.writestr(member, content, compress_type=zipfile.ZIP_DEFLATED)
        return buffer.getvalue()

    def _extract_package_bytes(self, content: bytes, target: Path) -> None:
        with zipfile.ZipFile(io.BytesIO(content)) as archive:
            run_packages._extract_package_archive(archive, target)

    def test_rejects_absolute_and_traversing_archive_paths(self):
        unsafe_names = (
            "../escape.txt",
            "/absolute.txt",
            "C:/absolute.txt",
            "run/../../escape.txt",
            r"run\..\escape.txt",
        )
        for unsafe_name in unsafe_names:
            with self.subTest(name=unsafe_name), tempfile.TemporaryDirectory() as tmp:
                content = self._build_package_bytes([(unsafe_name, b"unsafe")])

                with self.assertRaises(ValueError):
                    self._extract_package_bytes(content, Path(tmp) / "extract")

                self.assertFalse((Path(tmp) / "escape.txt").exists())

    def test_rejects_windows_reserved_device_names(self):
        for reserved_name in ("CON", "nul.txt", "Com1.json", "LPT9"):
            with self.subTest(name=reserved_name), tempfile.TemporaryDirectory() as tmp:
                content = self._build_package_bytes(
                    [(f"run/{reserved_name}", b"unsafe")]
                )

                with self.assertRaises(ValueError):
                    self._extract_package_bytes(content, Path(tmp) / "extract")

    def test_rejects_symlink_and_special_archive_members(self):
        for file_type in (stat.S_IFLNK, stat.S_IFIFO):
            with self.subTest(file_type=file_type), tempfile.TemporaryDirectory() as tmp:
                info = zipfile.ZipInfo("run/unsafe-member")
                info.create_system = 3
                info.external_attr = (file_type | 0o777) << 16
                content = self._build_package_bytes([(info, b"target")])

                with self.assertRaises(ValueError):
                    self._extract_package_bytes(content, Path(tmp) / "extract")

    def test_rejects_duplicate_case_insensitive_paths(self):
        content = self._build_package_bytes(
            [("run/item.json", b"{}"), ("run/ITEM.json", b"{}")]
        )
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                self._extract_package_bytes(content, Path(tmp) / "extract")

    def test_rejects_excessive_archive_member_count(self):
        content = self._build_package_bytes(
            [("run/run_manifest.json", b"{}"), ("run/extra.json", b"{}")]
        )
        with tempfile.TemporaryDirectory() as tmp, patch.object(
            run_packages, "MAX_PACKAGE_MEMBER_COUNT", 2
        ):
            with self.assertRaises(ValueError):
                self._extract_package_bytes(content, Path(tmp) / "extract")

    def test_rejects_excessive_implicit_directory_count(self):
        content = self._build_package_bytes(
            [("run/first/item", b""), ("run/second/item", b"")]
        )
        with tempfile.TemporaryDirectory() as tmp, patch.object(
            run_packages, "MAX_PACKAGE_DIRECTORY_COUNT", 2
        ):
            with self.assertRaises(ValueError):
                self._extract_package_bytes(content, Path(tmp) / "extract")

    def test_rejects_excessive_path_depth(self):
        content = self._build_package_bytes([("run/a/b/item", b"")])
        with tempfile.TemporaryDirectory() as tmp, patch.object(
            run_packages, "MAX_PACKAGE_MEMBER_PATH_DEPTH", 3
        ):
            with self.assertRaises(ValueError):
                self._extract_package_bytes(content, Path(tmp) / "extract")

    def test_rejects_oversized_single_member(self):
        content = self._build_package_bytes([("run/large.bin", os.urandom(129))])
        with tempfile.TemporaryDirectory() as tmp, patch.object(
            run_packages, "MAX_PACKAGE_MEMBER_SIZE", 128
        ):
            with self.assertRaises(ValueError):
                self._extract_package_bytes(content, Path(tmp) / "extract")

    def test_rejects_excessive_total_expanded_size(self):
        content = self._build_package_bytes(
            [("run/first.bin", os.urandom(40)), ("run/second.bin", os.urandom(40))]
        )
        with zipfile.ZipFile(io.BytesIO(content)) as archive:
            infos = archive.infolist()
            total_size = sum(info.file_size for info in infos)
            largest_member = max(info.file_size for info in infos)
        with (
            tempfile.TemporaryDirectory() as tmp,
            patch.object(run_packages, "MAX_PACKAGE_MEMBER_SIZE", largest_member),
            patch.object(
                run_packages,
                "MAX_PACKAGE_TOTAL_UNCOMPRESSED_SIZE",
                total_size - 1,
            ),
        ):
            with self.assertRaises(ValueError):
                self._extract_package_bytes(content, Path(tmp) / "extract")

    def test_rejects_high_compression_ratio(self):
        content = self._build_package_bytes(
            [("run/compression-bomb.txt", b"A" * (1024 * 1024))]
        )
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                self._extract_package_bytes(content, Path(tmp) / "extract")

    def test_rejects_oversized_package_manifest(self):
        manifest = {
            "kind": run_packages.PACKAGE_KIND,
            "schema_version": run_packages.PACKAGE_SCHEMA_VERSION,
            "padding": base64.b64encode(
                os.urandom(run_packages.MAX_PACKAGE_MANIFEST_SIZE)
            ).decode("ascii"),
        }
        content = self._build_package_bytes(
            [("run/run_manifest.json", b"{}")],
            manifest=manifest,
            manifest_compression=zipfile.ZIP_STORED,
        )
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                self._extract_package_bytes(content, Path(tmp) / "extract")

    def test_streams_members_without_extractall(self):
        content = self._build_package_bytes(
            [("run/run_manifest.json", b'{"run_id": "source"}')]
        )
        with tempfile.TemporaryDirectory() as tmp, patch.object(
            zipfile.ZipFile,
            "extractall",
            side_effect=AssertionError("extractall must not be used"),
        ) as extractall:
            extract_root = Path(tmp) / "extract"
            self._extract_package_bytes(content, extract_root)

            self.assertEqual(
                (extract_root / "run" / "run_manifest.json").read_bytes(),
                b'{"run_id": "source"}',
            )
            extractall.assert_not_called()

    def test_rejected_package_does_not_create_target_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            package_path = root / "unsafe.zip"
            package_path.write_bytes(
                self._build_package_bytes([("../escape.txt", b"unsafe")])
            )
            runs_root = root / "runs"
            runs_root.mkdir()
            callback = Mock(side_effect=AssertionError("callback must not run"))

            with self.assertRaises(ValueError):
                run_packages.import_run_package(
                    package_path=package_path,
                    runs_root=runs_root,
                    new_run_id="run-rejected",
                    builtin_source=False,
                    utc_now=callback,
                    load_manifest=callback,
                    write_json=callback,
                    discover_artifacts=callback,
                    serialize_manifest=callback,
                )

            self.assertFalse((runs_root / "run-rejected").exists())

    def test_export_refuses_to_publish_package_that_exceeds_import_limits(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_dir = Path(tmp) / "run-source"
            run_dir.mkdir()
            (run_dir / "run_manifest.json").write_bytes(os.urandom(129))
            manifest = {"run_id": "run-source", "novel_id": "test", "status": "ready"}

            with patch.object(run_packages, "MAX_PACKAGE_MEMBER_SIZE", 128):
                with self.assertRaises(ValueError):
                    run_packages.export_run_package(
                        run_id="run-source",
                        run_dir=run_dir,
                        manifest=manifest,
                        builtin=False,
                        utc_now=lambda: "2026-07-27T00:00:00Z",
                    )

            self.assertEqual(list((run_dir / "exports").glob("*.zip")), [])


if __name__ == "__main__":
    unittest.main()
