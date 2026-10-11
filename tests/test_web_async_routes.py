from __future__ import annotations

import pytest

fastapi = pytest.importorskip(
    "fastapi",
    reason="Async Web API tests require FastAPI.",
)
httpx = pytest.importorskip(
    "httpx",
    reason="Async Web API tests require httpx/httpx2.",
)

from src.web.app import create_app
from src.web.workflow import WebRunService


@pytest.mark.asyncio
async def test_health_route_through_asgi_transport(
    web_run_service: WebRunService,
) -> None:
    """Exercise the ASGI stack without TestClient's synchronous adapter."""

    transport = httpx.ASGITransport(app=create_app(web_run_service))
    async with httpx.AsyncClient(
        transport=transport,
        base_url="http://testserver",
    ) as client:
        response = await client.get("/api/web/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


@pytest.mark.asyncio
async def test_auth_middleware_rejects_missing_bearer_token(
    web_run_service: WebRunService,
) -> None:
    """Cover async middleware and a protected endpoint in one request."""

    transport = httpx.ASGITransport(
        app=create_app(web_run_service, auth_token="test-secret")
    )
    async with httpx.AsyncClient(
        transport=transport,
        base_url="http://testserver",
    ) as client:
        response = await client.get("/api/web/settings/model")

    assert response.status_code == 401
    assert response.headers["www-authenticate"] == "Bearer"
