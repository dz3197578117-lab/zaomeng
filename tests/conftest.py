from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path

import pytest

from src.core.config import clear_config_cache
from src.utils.file_utils import clear_markdown_data_cache
from src.web.workflow import WebRunService


@pytest.fixture
def isolated_runtime_caches() -> Iterator[None]:
    """Keep process-wide file caches from leaking between integration tests."""

    clear_config_cache()
    clear_markdown_data_cache()
    try:
        yield
    finally:
        clear_config_cache()
        clear_markdown_data_cache()


@pytest.fixture
def web_storage_root(tmp_path: Path) -> Path:
    """Return a per-test storage root for the Web UI service."""

    return tmp_path / "web-storage"


@pytest.fixture
def web_run_service(
    web_storage_root: Path,
    isolated_runtime_caches: None,
) -> WebRunService:
    """Build an isolated WebRunService shared by route-level tests."""

    return WebRunService(web_storage_root)
