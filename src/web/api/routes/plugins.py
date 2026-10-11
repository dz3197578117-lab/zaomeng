from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException

from src.plugin_system import PluginError
from src.web.api.deps import get_run_service
from src.web.api.schemas import (
    InspectPluginPackageRequest,
    InstallPluginPackageRequest,
    InvokePluginChatActionRequest,
    InvokeTemporaryNpcGeneratorRequest,
    SetGenerationEnhancerStateRequest,
    UpdatePluginConfigRequest,
)
from src.web.workflow import WebRunService


router = APIRouter()


@router.post("/api/web/plugins/packages/inspect")
def inspect_plugin_package(
    payload: InspectPluginPackageRequest,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.inspect_plugin_package(
            filename=payload.filename,
            content_base64=payload.content_base64,
        )
    except PluginError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/api/web/plugins/packages/{token}/install")
def install_plugin_package(
    token: str,
    payload: InstallPluginPackageRequest,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.install_inspected_plugin_package(
            token,
            confirm_permissions=payload.confirm_permissions,
            allow_update=payload.allow_update,
        )
    except PluginError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.get("/api/web/plugins")
def list_plugins(
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    return {"items": run_service.list_plugins()}


@router.post("/api/web/plugins/refresh")
def refresh_plugins(
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    return {"items": run_service.refresh_plugins()}


@router.post("/api/web/plugins/{plugin_id}/enable")
def enable_plugin(
    plugin_id: str,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.enable_plugin(plugin_id)
    except PluginError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/api/web/plugins/{plugin_id}/disable")
def disable_plugin(
    plugin_id: str,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.disable_plugin(plugin_id)
    except PluginError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.delete("/api/web/plugins/{plugin_id}")
def uninstall_plugin(
    plugin_id: str,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.uninstall_plugin(plugin_id)
    except PluginError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.get("/api/web/plugins/{plugin_id}/logs")
def list_plugin_logs(
    plugin_id: str,
    limit: int = 100,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.list_plugin_logs(plugin_id, limit=limit)
    except PluginError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.get("/api/web/plugins/{plugin_id}/config")
def get_plugin_config(
    plugin_id: str,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.get_plugin_config(plugin_id)
    except PluginError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.put("/api/web/plugins/{plugin_id}/config")
def update_plugin_config(
    plugin_id: str,
    payload: UpdatePluginConfigRequest,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.update_plugin_config(plugin_id, payload.config)
    except PluginError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post(
    "/api/web/runs/{run_id}/dialogue/sessions/{session_id}"
    "/plugins/{plugin_id}/actions/{action_id}"
)
def invoke_plugin_chat_action(
    run_id: str,
    session_id: str,
    plugin_id: str,
    action_id: str,
    payload: InvokePluginChatActionRequest,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.invoke_plugin_chat_action(
            plugin_id,
            action_id,
            run_id=run_id,
            session_id=session_id,
            seed_text=payload.seed_text,
            direction=payload.direction,
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Session not found.") from exc
    except (PluginError, ValueError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post(
    "/api/web/runs/{run_id}/dialogue/sessions/{session_id}"
    "/plugins/{plugin_id}/npc-generators/{generator_id}"
)
def invoke_plugin_temporary_npc_generator(
    run_id: str,
    session_id: str,
    plugin_id: str,
    generator_id: str,
    payload: InvokeTemporaryNpcGeneratorRequest,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.invoke_plugin_temporary_npc_generator(
            plugin_id,
            generator_id,
            run_id=run_id,
            session_id=session_id,
            direction=payload.direction,
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Session not found.") from exc
    except (PluginError, ValueError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.put(
    "/api/web/runs/{run_id}/dialogue/sessions/{session_id}"
    "/plugins/{plugin_id}/enhancers/{enhancer_id}/state"
)
def set_generation_enhancer_state(
    run_id: str,
    session_id: str,
    plugin_id: str,
    enhancer_id: str,
    payload: SetGenerationEnhancerStateRequest,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.set_generation_enhancer_state(
            plugin_id,
            enhancer_id,
            run_id=run_id,
            session_id=session_id,
            enabled=payload.enabled,
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Session not found.") from exc
    except (PluginError, ValueError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
