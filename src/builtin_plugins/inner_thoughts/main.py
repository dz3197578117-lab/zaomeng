from __future__ import annotations

from typing import Any


class InnerThoughtsPlugin:
    def activate(self, host: Any) -> None:
        self._host = host

    def deactivate(self) -> None:
        self._host = None

    def enhance_generation(
        self, enhancer_id: str, request: dict[str, Any]
    ) -> dict[str, bool]:
        if enhancer_id != "inner-thoughts":
            raise ValueError(f"Unsupported generation enhancer: {enhancer_id!r}.")
        return {"include_inner_thoughts": True}


def create_plugin() -> InnerThoughtsPlugin:
    return InnerThoughtsPlugin()
