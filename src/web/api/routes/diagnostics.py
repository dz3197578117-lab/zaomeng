from __future__ import annotations

import json

from fastapi import APIRouter, Depends, Response

from src.web.api.deps import get_run_service
from src.web.workflow import WebRunService


router = APIRouter()


@router.get("/api/web/diagnostics/export")
def export_diagnostics(run_service: WebRunService = Depends(get_run_service)) -> Response:
    content = json.dumps(run_service.build_diagnostics_report(), ensure_ascii=False, indent=2)
    return Response(
        content=content,
        media_type="application/json; charset=utf-8",
        headers={"Content-Disposition": 'attachment; filename="zaomeng-diagnostics.json"'},
    )
