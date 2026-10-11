from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException

from src.web.api.deps import get_run_service
from src.web.api.schemas import (
    SearchOriginalKnowledgeRequest,
    UpdateOriginalKnowledgeBoundaryRequest,
)
from src.web.workflow import WebRunService


router = APIRouter()


@router.get("/api/web/runs/{run_id}/original-knowledge")
def get_original_knowledge(
    run_id: str,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.get_original_knowledge(run_id)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Run or original source not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/api/web/runs/{run_id}/original-knowledge/rebuild")
def rebuild_original_knowledge(
    run_id: str,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.rebuild_original_knowledge(run_id)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Run or original source not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/api/web/runs/{run_id}/original-knowledge/search")
def search_original_knowledge(
    run_id: str,
    payload: SearchOriginalKnowledgeRequest,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return {
            "items": run_service.search_original_knowledge(
                run_id,
                query=payload.query,
                participants=payload.participants,
                limit=payload.limit,
            )
        }
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Run or original source not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.put("/api/web/runs/{run_id}/original-knowledge/entries/{entry_id}/boundary")
def update_original_knowledge_boundary(
    run_id: str,
    entry_id: str,
    payload: UpdateOriginalKnowledgeBoundaryRequest,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.update_original_knowledge_boundary(
            run_id,
            entry_id,
            visibility=payload.visibility,
            knowers=payload.knowers,
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Entry or run not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
