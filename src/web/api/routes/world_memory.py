from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException

from src.web.api.compat import model_to_dict
from src.web.api.deps import get_run_service
from src.web.api.schemas import SaveWorldFactRequest
from src.web.workflow import WebRunService


router = APIRouter()


@router.get("/api/web/runs/{run_id}/world-memory")
def get_world_memory(
    run_id: str,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.get_world_memory(run_id)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Run not found.") from exc


@router.post("/api/web/runs/{run_id}/world-memory/facts")
def create_world_fact(
    run_id: str,
    payload: SaveWorldFactRequest,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.save_world_fact(run_id, fields=model_to_dict(payload))
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Run not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.put("/api/web/runs/{run_id}/world-memory/facts/{fact_id}")
def update_world_fact(
    run_id: str,
    fact_id: str,
    payload: SaveWorldFactRequest,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.save_world_fact(
            run_id,
            fact_id=fact_id,
            fields=model_to_dict(payload),
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Fact or run not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.delete("/api/web/runs/{run_id}/world-memory/facts/{fact_id}")
def delete_world_fact(
    run_id: str,
    fact_id: str,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, str]:
    try:
        return run_service.delete_world_fact(run_id, fact_id)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Fact or run not found.") from exc
