#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
LLM client with local fallback.

Responsibilities:
- token estimation
- cost/budget tracking
- provider-aware chat completion
"""

from __future__ import annotations

import json
import logging
import os
import threading
import tempfile
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

import requests

try:
    import tiktoken
except ImportError:
    tiktoken = None

from .config import Config
from .exceptions import BudgetExceededError, LLMRequestError, MissingAPIKeyError
from src.utils.file_utils import load_markdown_data

logger = logging.getLogger(__name__)
_TIKTOKEN_FALLBACK_LOGGED = False


def _reasoning_efforts_for_model(
    *, provider: str, base_url: str, model: str
) -> tuple[str, ...]:
    normalized = str(model or "").strip().lower()
    if provider == "openai-compatible" and normalized.startswith("step-"):
        if normalized == "step-3.5-flash-2603":
            return ("auto", "low", "high")
        return ("auto", "low", "medium", "high")
    if normalized.startswith("deepseek-v4-"):
        if "api.deepseek.com" in str(base_url or "").lower():
            return ("auto", "off", "low", "medium", "high", "xhigh")
        return ("auto", "low", "medium", "high")
    if provider in {"openai", "openai-compatible"}:
        if normalized.startswith("gpt-5"):
            if provider == "openai":
                return ("auto", "off", "low", "medium", "high", "xhigh")
            return ("auto", "low", "medium", "high")
        if normalized.startswith(("o1", "o3", "o4")):
            return ("auto", "low", "medium", "high")
    if (
        provider == "openai-compatible"
        and "dashscope.aliyuncs.com" in str(base_url or "").lower()
        and normalized.startswith("qwen")
    ):
        return ("auto", "off")
    return ("auto",)


def _apply_reasoning_controls(
    payload: Dict[str, Any],
    *,
    provider: str,
    base_url: str,
    model: str,
    reasoning_effort: str,
) -> None:
    normalized_model = str(model or "").strip().lower()
    supported_efforts = _reasoning_efforts_for_model(
        provider=provider, base_url=base_url, model=model
    )
    if reasoning_effort not in supported_efforts:
        return
    is_deepseek_v4 = normalized_model.startswith("deepseek-v4-")
    is_stepfun_reasoning = (
        provider == "openai-compatible" and normalized_model.startswith("step-")
    )
    is_direct_deepseek_v4 = (
        is_deepseek_v4 and "api.deepseek.com" in str(base_url or "").lower()
    )
    is_openai_reasoning = (
        provider in {"openai", "openai-compatible"}
        and normalized_model.startswith(("gpt-5", "o1", "o3", "o4"))
    )
    is_dashscope_qwen = (
        provider == "openai-compatible"
        and "dashscope.aliyuncs.com" in str(base_url or "").lower()
        and normalized_model.startswith("qwen")
    )
    if reasoning_effort == "off":
        if is_direct_deepseek_v4:
            payload["thinking"] = {"type": "disabled"}
        elif is_openai_reasoning:
            payload["reasoning_effort"] = "none"
        elif is_dashscope_qwen:
            payload["enable_thinking"] = False
        return
    if not reasoning_effort or reasoning_effort == "auto":
        return
    if is_openai_reasoning or is_stepfun_reasoning:
        payload["reasoning_effort"] = reasoning_effort
        return
    if not is_deepseek_v4:
        return
    payload["reasoning_effort"] = {
        "medium": "low",
        "xhigh": "max",
    }.get(reasoning_effort, reasoning_effort)


def _non_negative_int(value: Any) -> int:
    try:
        return max(0, int(value or 0))
    except (OverflowError, TypeError, ValueError):
        return 0


def _metric_available(value: Any) -> bool:
    return value is not None and value != ""


def _normalized_cache_result(
    *,
    observable: bool,
    hit_tokens: int = 0,
    miss_tokens: int = 0,
    creation_tokens: int = 0,
    input_tokens: int = 0,
    fallback_hit_rate: Any = None,
) -> Dict[str, Any]:
    hit = _non_negative_int(hit_tokens)
    miss = _non_negative_int(miss_tokens)
    creation = _non_negative_int(creation_tokens)
    total = max(_non_negative_int(input_tokens), hit + miss + creation)
    hit_rate: Optional[float] = None
    if observable:
        if total > 0:
            hit_rate = hit / total
        else:
            try:
                hit_rate = min(1.0, max(0.0, float(fallback_hit_rate)))
            except (TypeError, ValueError):
                hit_rate = 0.0
    return {
        "observable": bool(observable),
        "hit_tokens": hit,
        "miss_tokens": miss,
        "creation_tokens": creation,
        "input_tokens": total,
        "hit_rate": hit_rate,
    }


def normalize_cache_usage(
    response: Any, *, prompt_tokens: int = 0
) -> Dict[str, Any]:
    """Normalize provider cache counters without treating absent data as a miss."""

    if not isinstance(response, dict):
        return _normalized_cache_result(
            observable=False, input_tokens=prompt_tokens
        )

    explicit = response.get("cache_usage")
    if isinstance(explicit, dict):
        metric_keys = {
            "hit_tokens",
            "miss_tokens",
            "creation_tokens",
            "input_tokens",
            "hit_rate",
        }
        observable = bool(
            explicit.get("observable", any(key in explicit for key in metric_keys))
        )
        return _normalized_cache_result(
            observable=observable,
            hit_tokens=explicit.get(
                "hit_tokens", explicit.get("cache_hit_tokens", 0)
            ),
            miss_tokens=explicit.get(
                "miss_tokens", explicit.get("cache_miss_tokens", 0)
            ),
            creation_tokens=explicit.get(
                "creation_tokens", explicit.get("cache_creation_tokens", 0)
            ),
            input_tokens=explicit.get("input_tokens", prompt_tokens),
            fallback_hit_rate=explicit.get("hit_rate"),
        )

    usage = response.get("usage", {})
    if not isinstance(usage, dict):
        usage = {}

    def _find(name: str) -> tuple[bool, Any]:
        if name in usage:
            return True, usage.get(name)
        if name in response:
            return True, response.get(name)
        return False, None

    read_present, cache_read = _find("cache_read_input_tokens")
    creation_present, cache_creation = _find("cache_creation_input_tokens")
    if (read_present and _metric_available(cache_read)) or (
        creation_present and _metric_available(cache_creation)
    ):
        ordinary_present, ordinary_input = _find("input_tokens")
        ordinary = _non_negative_int(
            ordinary_input if ordinary_present else prompt_tokens
        )
        read = _non_negative_int(cache_read)
        creation = _non_negative_int(cache_creation)
        return _normalized_cache_result(
            observable=True,
            hit_tokens=read,
            miss_tokens=ordinary,
            creation_tokens=creation,
            input_tokens=ordinary + read + creation,
        )

    hit_present, cache_hit = _find("prompt_cache_hit_tokens")
    miss_present, cache_miss = _find("prompt_cache_miss_tokens")
    if (hit_present and _metric_available(cache_hit)) or (
        miss_present and _metric_available(cache_miss)
    ):
        hit = _non_negative_int(cache_hit)
        total = _non_negative_int(
            usage.get("prompt_tokens", response.get("prompt_tokens", prompt_tokens))
        )
        miss = (
            _non_negative_int(cache_miss)
            if miss_present
            else max(0, total - hit)
        )
        return _normalized_cache_result(
            observable=True,
            hit_tokens=hit,
            miss_tokens=miss,
            input_tokens=max(total, hit + miss),
        )

    details = usage.get("prompt_tokens_details", {})
    if not isinstance(details, dict):
        details = {}
    if "cached_tokens" in details and _metric_available(details.get("cached_tokens")):
        hit = _non_negative_int(details.get("cached_tokens"))
        total = _non_negative_int(
            usage.get("prompt_tokens", response.get("prompt_tokens", prompt_tokens))
        )
        total = max(total, hit)
        return _normalized_cache_result(
            observable=True,
            hit_tokens=hit,
            miss_tokens=max(0, total - hit),
            input_tokens=total,
        )

    return _normalized_cache_result(
        observable=False, input_tokens=prompt_tokens
    )


def strip_cache_static_markers(
    messages: List[Dict[str, Any]],
) -> List[Dict[str, Any]]:
    """Remove the internal cache hint before calling non-Anthropic providers."""

    return [
        {key: value for key, value in message.items() if key != "cache_static"}
        for message in messages
    ]


def _log_tiktoken_fallback(exc: Exception) -> None:
    global _TIKTOKEN_FALLBACK_LOGGED
    if _TIKTOKEN_FALLBACK_LOGGED:
        return
    _TIKTOKEN_FALLBACK_LOGGED = True
    if isinstance(exc, (ImportError, ModuleNotFoundError)):
        logger.info(
            "tiktoken unavailable, using heuristic token counting instead: %s", exc
        )
        return
    logger.warning(
        "Failed to initialize tiktoken encoder, falling back to heuristic token counting: %s",
        exc,
    )


class LLMClient:
    """Provider-aware chat client with automatic host/env detection."""

    HOST_BRIDGE_ENV_URL = "ZAOMENG_HOST_BRIDGE_URL"
    HOST_BRIDGE_ENV_MODEL = "ZAOMENG_HOST_BRIDGE_MODEL"
    HOST_BRIDGE_ENV_TOKEN = "ZAOMENG_HOST_BRIDGE_TOKEN"
    DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
    DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com/v1"
    DEFAULT_OLLAMA_BASE_URL = "http://127.0.0.1:11434"
    DEFAULT_HOST_BRIDGE_PATH = "/chat/completions"
    DEFAULT_OPENAI_MODEL = "gpt-4.1-mini"
    DEFAULT_ANTHROPIC_MODEL = "claude-3-5-haiku-latest"
    DEFAULT_OLLAMA_MODEL = "qwen2.5:7b-instruct"
    DEFAULT_HOST_BRIDGE_MODEL = "host-default"
    AUTO_PROVIDER = "auto"
    LOCAL_PROVIDER = "local-rule-engine"
    DEFAULT_RETRY_STATUS_CODES = (408, 429, 500, 502, 503, 504)
    COST_STATS_FLUSH_INTERVAL_SECONDS = 2.0
    COST_STATS_FLUSH_BATCH_SIZE = 20

    def __init__(self, config: Optional[Config] = None):
        self.config = config or Config()
        self.cost_config = self.config.get_cost_config()
        self.engine_config = self.config.get("engine", {})
        self.llm_config = self.config.get_llm_config()

        self.session_cost = 0.0
        self.daily_cost = 0.0
        self.last_reset_date = datetime.now().date()
        self.request_count = 0
        self.total_tokens = 0
        self._usage_lock = threading.RLock()
        self._stats_write_lock = threading.Lock()
        self._stats_flush_timer: threading.Timer | None = None
        self._pending_usage_records = 0
        self._usage_version = 0
        self._cost_stats_path = Path(self.config.project_root) / "data" / "cost_stats.json"

        self._load_cost_stats()

        try:
            self.encoder = tiktoken.get_encoding("cl100k_base") if tiktoken else None
        except (
            Exception
        ) as exc:  # pragma: no cover - depends on local tiktoken/network state
            _log_tiktoken_fallback(exc)
            self.encoder = None

    def _load_cost_stats(self):
        stats_file = self._cost_stats_path
        legacy_stats_file = Path(self.config.project_root) / "data" / "cost_stats.md"
        data: dict[str, Any] = {}
        source_path = stats_file
        if stats_file.exists():
            try:
                loaded = json.loads(stats_file.read_text(encoding="utf-8"))
                if isinstance(loaded, dict):
                    data = loaded
            except (json.JSONDecodeError, OSError) as exc:
                logger.warning("Failed to load cost stats from %s: %s", stats_file, exc)
        elif legacy_stats_file.exists():
            source_path = legacy_stats_file
            try:
                data = load_markdown_data(legacy_stats_file, default={}) or {}
            except (OSError, TypeError, ValueError) as exc:
                logger.warning("Failed to load cost stats from %s: %s", legacy_stats_file, exc)

        try:
            self.daily_cost = float(data.get("daily_cost", 0.0))
            self.request_count = int(data.get("total_requests", 0) or 0)
            self.total_tokens = int(data.get("total_tokens", 0) or 0)
            last = data.get("last_reset_date")
            if last:
                self.last_reset_date = datetime.fromisoformat(str(last)).date()
        except (TypeError, ValueError) as exc:
            logger.warning("Invalid cost stats in %s: %s", source_path, exc)

        reset = self._check_reset_daily()
        if (legacy_stats_file.exists() and not stats_file.exists()) or reset:
            self._save_cost_stats()

    def _save_cost_stats(self):
        with self._usage_lock:
            version = self._usage_version
            payload = {
                "daily_cost": self.daily_cost,
                "last_reset_date": self.last_reset_date.isoformat(),
                "total_requests": self.request_count,
                "total_tokens": self.total_tokens,
            }
        stats_file = self._cost_stats_path
        stats_file.parent.mkdir(parents=True, exist_ok=True)
        text = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
        temp_name = ""
        with self._stats_write_lock:
            try:
                with tempfile.NamedTemporaryFile(
                    "w",
                    encoding="utf-8",
                    dir=stats_file.parent,
                    prefix=f".{stats_file.name}.",
                    suffix=".tmp",
                    delete=False,
                ) as temp_file:
                    temp_name = temp_file.name
                    temp_file.write(text)
                    temp_file.flush()
                    os.fsync(temp_file.fileno())
                os.replace(temp_name, stats_file)
            finally:
                if temp_name:
                    temp_path = Path(temp_name)
                    if temp_path.exists():
                        temp_path.unlink()
        with self._usage_lock:
            if self._usage_version == version:
                self._pending_usage_records = 0

    def _check_reset_daily(self) -> bool:
        today = datetime.now().date()
        if today > self.last_reset_date:
            self.daily_cost = 0.0
            self.last_reset_date = today
            self._usage_version += 1
            return True
        return False

    def _schedule_cost_stats_flush(self) -> None:
        if self._stats_flush_timer is not None:
            return
        timer = threading.Timer(self.COST_STATS_FLUSH_INTERVAL_SECONDS, self._flush_cost_stats_from_timer)
        timer.daemon = True
        self._stats_flush_timer = timer
        timer.start()

    def _flush_cost_stats_from_timer(self) -> None:
        with self._usage_lock:
            self._stats_flush_timer = None
            has_pending = self._pending_usage_records > 0
        if has_pending:
            try:
                self._save_cost_stats()
            except OSError as exc:
                logger.warning("Failed to persist cost stats: %s", exc)
        with self._usage_lock:
            if self._pending_usage_records > 0:
                self._schedule_cost_stats_flush()

    def flush_cost_stats(self) -> None:
        with self._usage_lock:
            timer = self._stats_flush_timer
            self._stats_flush_timer = None
            has_pending = self._pending_usage_records > 0
        if timer is not None:
            timer.cancel()
        if has_pending:
            try:
                self._save_cost_stats()
            except OSError as exc:
                logger.warning("Failed to persist cost stats: %s", exc)
                with self._usage_lock:
                    self._schedule_cost_stats_flush()

    def _check_budget(self):
        daily_budget = float(self.cost_config.get("daily_budget_usd", 10.0))
        if self.daily_cost >= daily_budget:
            raise BudgetExceededError(
                f"日预算已用完: ${self.daily_cost:.2f} >= ${daily_budget:.2f}"
            )
        threshold = float(self.cost_config.get("warning_threshold", 0.8))
        if self.daily_cost >= daily_budget * threshold:
            remaining = daily_budget - self.daily_cost
            logger.warning(
                "警告: 日预算已使用 %.1f%%", self.daily_cost / daily_budget * 100
            )
            logger.warning("剩余预算: $%.2f", remaining)

    def count_tokens(self, text: str) -> int:
        if not text:
            return 0
        if self.encoder:
            return len(self.encoder.encode(text))
        return max(1, len(text) // 2)

    def _calculate_cost(self, prompt_tokens: int, completion_tokens: int) -> float:
        unit = float(self.engine_config.get("pseudo_cost_per_1k_tokens_usd", 0.001))
        return ((prompt_tokens + completion_tokens) / 1000.0) * unit

    def estimate_cost(self, text: str, expected_completion_ratio: float = 0.5) -> float:
        prompt_tokens = self.count_tokens(text)
        completion_tokens = int(prompt_tokens * expected_completion_ratio)
        return self._calculate_cost(prompt_tokens, completion_tokens)

    def record_usage(
        self, prompt_tokens: int, completion_tokens: int = 0, elapsed_time: float = 0.0
    ):
        with self._usage_lock:
            self._check_reset_daily()
            self._check_budget()
            total_tokens = prompt_tokens + completion_tokens
            cost = self._calculate_cost(prompt_tokens, completion_tokens)
            self.session_cost += cost
            self.daily_cost += cost
            self.request_count += 1
            self.total_tokens += total_tokens
            self._usage_version += 1
            self._pending_usage_records += 1
            flush_now = self._pending_usage_records >= self.COST_STATS_FLUSH_BATCH_SIZE
            if not flush_now:
                self._schedule_cost_stats_flush()
        if flush_now:
            self.flush_cost_stats()
        if self.cost_config.get("enable_cost_warning", True):
            logger.info(
                f"[Tokens: {prompt_tokens}+{completion_tokens}={total_tokens}] "
                f"[Cost: ${cost:.4f}] [Time: {elapsed_time:.2f}s]"
            )
            logger.info(
                "[Session: $%.4f] [Daily: $%.4f]", self.session_cost, self.daily_cost
            )
        return {
            "prompt_tokens": prompt_tokens,
            "completion_tokens": completion_tokens,
            "total_tokens": total_tokens,
            "cost": cost,
            "elapsed_time": elapsed_time,
        }

    def provider_name(self) -> str:
        configured = (
            str(self.llm_config.get("provider", self.AUTO_PROVIDER)).strip().lower()
        )
        if configured and configured not in {self.AUTO_PROVIDER, self.LOCAL_PROVIDER}:
            return configured
        return self._infer_provider_from_environment()

    def is_generation_enabled(self) -> bool:
        return self.provider_name() != self.LOCAL_PROVIDER

    def chat_completion(
        self,
        messages: List[Dict[str, Any]],
        model: Optional[str] = None,
        temperature: Optional[float] = None,
        max_tokens: Optional[int] = None,
        stream: bool = False,
    ) -> Dict[str, Any]:
        if stream:
            return self.chat_completion_stream(
                messages,
                model=model,
                temperature=temperature,
                max_tokens=max_tokens,
            )

        provider = self.provider_name()
        start = time.time()
        prompt = "\n".join(
            f"{m.get('role', 'user')}: {m.get('content', '')}" for m in messages
        )
        prompt_tokens = self.count_tokens(prompt)

        if provider == self.LOCAL_PROVIDER:
            content = "本地模式未启用云模型。请使用规则引擎发言。"
            completion_tokens = self.count_tokens(content)
            usage = self.record_usage(
                prompt_tokens, completion_tokens, time.time() - start
            )
            usage["content"] = content
            usage["model"] = self.LOCAL_PROVIDER
            usage["provider"] = provider
            usage["cache_usage"] = normalize_cache_usage(
                {}, prompt_tokens=prompt_tokens
            )
            return usage

        result = self._dispatch_chat_completion(
            provider=provider,
            messages=messages,
            model=self._resolve_model_name(provider, model),
            temperature=temperature,
            max_tokens=max_tokens,
        )
        prompt_usage = int(result.get("prompt_tokens", prompt_tokens))
        cache_usage = result.get(
            "cache_usage",
            normalize_cache_usage(result.get("raw", {}), prompt_tokens=prompt_usage),
        )
        if isinstance(cache_usage, dict) and bool(cache_usage.get("observable")):
            prompt_usage = max(
                prompt_usage,
                _non_negative_int(cache_usage.get("input_tokens", 0)),
            )
        completion_usage = int(
            result.get(
                "completion_tokens", self.count_tokens(result.get("content", ""))
            )
        )
        usage = self.record_usage(prompt_usage, completion_usage, time.time() - start)
        usage["content"] = result.get("content", "")
        usage["model"] = result.get("model", model or self.llm_config.get("model", ""))
        usage["provider"] = provider
        usage["finish_reason"] = str(result.get("finish_reason", "")).strip()
        usage["raw"] = result.get("raw", {})
        usage["cache_usage"] = cache_usage
        return usage

    def chat_completion_stream(
        self,
        messages: List[Dict[str, Any]],
        model: Optional[str] = None,
        temperature: Optional[float] = None,
        max_tokens: Optional[int] = None,
        *,
        on_delta: Callable[[str], None] | None = None,
    ) -> Dict[str, Any]:
        """Stream provider text deltas while returning a normal completion result.

        ``on_delta`` receives content only, never provider framing or reasoning tokens.
        Callers which need a non-content activity signal may attach an optional
        ``on_reasoning`` callable attribute to it. The hook receives reasoning
        deltas, while the visible content callback remains clean.
        The returned dictionary has the same usage/content/model fields as
        :meth:`chat_completion`, so callers can keep their existing parsing and cost
        accounting. Host bridge and local-rule providers fall back to one callback.
        """

        provider = self.provider_name()
        start = time.time()
        prompt = "\n".join(
            f"{message.get('role', 'user')}: {message.get('content', '')}"
            for message in messages
        )
        estimated_prompt_tokens = self.count_tokens(prompt)

        if provider == self.LOCAL_PROVIDER:
            content = "本地模式未启用云模型。请使用规则引擎发言。"
            if callable(on_delta):
                on_delta(content)
            completion_tokens = self.count_tokens(content)
            usage = self.record_usage(
                estimated_prompt_tokens,
                completion_tokens,
                time.time() - start,
            )
            usage["content"] = content
            usage["model"] = self.LOCAL_PROVIDER
            usage["provider"] = provider
            usage["finish_reason"] = "stop"
            usage["raw"] = {}
            usage["cache_usage"] = normalize_cache_usage(
                {}, prompt_tokens=estimated_prompt_tokens
            )
            return usage

        resolved_model = self._resolve_model_name(provider, model)
        if provider == "host-bridge":
            result = self._chat_host_bridge(
                messages=messages,
                model=resolved_model,
                temperature=temperature,
                max_tokens=max_tokens,
            )
            content = str(result.get("content", ""))
            if content and callable(on_delta):
                on_delta(content)
        else:
            result = self._dispatch_chat_completion_stream(
                provider=provider,
                messages=messages,
                model=resolved_model,
                temperature=temperature,
                max_tokens=max_tokens,
                on_delta=on_delta,
            )

        prompt_usage = _non_negative_int(result.get("prompt_tokens"))
        if prompt_usage <= 0:
            prompt_usage = estimated_prompt_tokens
        completion_usage = _non_negative_int(result.get("completion_tokens"))
        if completion_usage <= 0:
            completion_usage = self.count_tokens(str(result.get("content", "")))
        cache_usage = result.get(
            "cache_usage",
            normalize_cache_usage(result.get("raw", {}), prompt_tokens=prompt_usage),
        )
        if isinstance(cache_usage, dict) and bool(cache_usage.get("observable")):
            prompt_usage = max(
                prompt_usage,
                _non_negative_int(cache_usage.get("input_tokens", 0)),
            )
        usage = self.record_usage(
            prompt_usage,
            completion_usage,
            time.time() - start,
        )
        usage["content"] = str(result.get("content", ""))
        usage["model"] = str(result.get("model", resolved_model)).strip() or resolved_model
        usage["provider"] = provider
        usage["finish_reason"] = str(result.get("finish_reason", "")).strip()
        usage["raw"] = result.get("raw", {})
        usage["cache_usage"] = cache_usage
        return usage

    def _infer_provider_from_environment(self) -> str:
        if self._host_bridge_url():
            return "host-bridge"
        if str(os.getenv("OPENAI_API_KEY", "")).strip():
            base_url = str(os.getenv("OPENAI_BASE_URL", "")).strip()
            return "openai-compatible" if base_url else "openai"
        if str(os.getenv("ANTHROPIC_API_KEY", "")).strip():
            return "anthropic"
        if str(os.getenv("OLLAMA_MODEL", "")).strip():
            return "ollama"
        return self.LOCAL_PROVIDER

    def _resolve_model_name(
        self, provider: str, requested_model: Optional[str] = None
    ) -> str:
        configured = str(requested_model or self.llm_config.get("model", "")).strip()
        if configured and configured != self.LOCAL_PROVIDER:
            return configured
        env_overrides = {
            "host-bridge": (self.HOST_BRIDGE_ENV_MODEL,),
            "openai": ("OPENAI_MODEL",),
            "openai-compatible": ("OPENAI_MODEL",),
            "anthropic": ("ANTHROPIC_MODEL",),
            "ollama": ("OLLAMA_MODEL",),
        }
        for env_name in env_overrides.get(provider, ()):
            value = str(os.getenv(env_name, "")).strip()
            if value:
                return value
        defaults = {
            "host-bridge": self.DEFAULT_HOST_BRIDGE_MODEL,
            "openai": self.DEFAULT_OPENAI_MODEL,
            "openai-compatible": self.DEFAULT_OPENAI_MODEL,
            "anthropic": self.DEFAULT_ANTHROPIC_MODEL,
            "ollama": self.DEFAULT_OLLAMA_MODEL,
        }
        return defaults.get(provider, configured or self.LOCAL_PROVIDER)

    def _dispatch_chat_completion(
        self,
        *,
        provider: str,
        messages: List[Dict[str, Any]],
        model: str,
        temperature: Optional[float],
        max_tokens: Optional[int],
    ) -> Dict[str, Any]:
        if provider in {"openai", "openai-compatible"}:
            return self._chat_openai_like(
                provider=provider,
                messages=messages,
                model=model,
                temperature=temperature,
                max_tokens=max_tokens,
            )
        if provider == "anthropic":
            return self._chat_anthropic(
                messages=messages,
                model=model,
                temperature=temperature,
                max_tokens=max_tokens,
            )
        if provider == "host-bridge":
            return self._chat_host_bridge(
                messages=messages,
                model=model,
                temperature=temperature,
                max_tokens=max_tokens,
            )
        if provider == "ollama":
            return self._chat_ollama(
                messages=messages,
                model=model,
                temperature=temperature,
                max_tokens=max_tokens,
            )
        raise ValueError(f"Unsupported llm.provider: {provider}")

    def _dispatch_chat_completion_stream(
        self,
        *,
        provider: str,
        messages: List[Dict[str, Any]],
        model: str,
        temperature: Optional[float],
        max_tokens: Optional[int],
        on_delta: Callable[[str], None] | None,
    ) -> Dict[str, Any]:
        if provider in {"openai", "openai-compatible"}:
            return self._chat_openai_like_stream(
                provider=provider,
                messages=messages,
                model=model,
                temperature=temperature,
                max_tokens=max_tokens,
                on_delta=on_delta,
            )
        if provider == "anthropic":
            return self._chat_anthropic_stream(
                messages=messages,
                model=model,
                temperature=temperature,
                max_tokens=max_tokens,
                on_delta=on_delta,
            )
        if provider == "ollama":
            return self._chat_ollama_stream(
                messages=messages,
                model=model,
                temperature=temperature,
                max_tokens=max_tokens,
                on_delta=on_delta,
            )
        raise ValueError(f"Unsupported streaming llm.provider: {provider}")

    def _chat_openai_like(
        self,
        *,
        provider: str,
        messages: List[Dict[str, Any]],
        model: str,
        temperature: Optional[float],
        max_tokens: Optional[int],
    ) -> Dict[str, Any]:
        api_key = self._resolve_api_key(provider)
        base_url = self._resolve_base_url(provider)
        payload: Dict[str, Any] = {
            "model": model,
            "messages": strip_cache_static_markers(messages),
            "temperature": self._resolve_temperature(temperature),
        }
        _apply_reasoning_controls(
            payload,
            provider=provider,
            base_url=base_url,
            model=model,
            reasoning_effort=str(
                self.llm_config.get("reasoning_effort", "off")
            ).strip().lower(),
        )
        resolved_max_tokens = self._resolve_max_tokens(max_tokens)
        if resolved_max_tokens:
            payload["max_tokens"] = resolved_max_tokens

        data = self._post_json(
            url=self._endpoint(base_url, "/chat/completions"),
            payload=payload,
            headers={
                "Authorization": f"Bearer {api_key}",
            },
        )
        choices = data.get("choices", [])
        message = choices[0].get("message", {}) if choices else {}
        usage = data.get("usage", {})
        return {
            "content": self._extract_text_content(message),
            "model": data.get("model", model),
            "finish_reason": (
                str(choices[0].get("finish_reason", "")).strip() if choices else ""
            ),
            "prompt_tokens": int(usage.get("prompt_tokens", 0)),
            "completion_tokens": int(usage.get("completion_tokens", 0)),
            "cache_usage": normalize_cache_usage(
                data, prompt_tokens=_non_negative_int(usage.get("prompt_tokens"))
            ),
            "raw": data,
        }

    def _chat_openai_like_stream(
        self,
        *,
        provider: str,
        messages: List[Dict[str, Any]],
        model: str,
        temperature: Optional[float],
        max_tokens: Optional[int],
        on_delta: Callable[[str], None] | None,
    ) -> Dict[str, Any]:
        api_key = self._resolve_api_key(provider)
        base_url = self._resolve_base_url(provider)
        payload: Dict[str, Any] = {
            "model": model,
            "messages": strip_cache_static_markers(messages),
            "temperature": self._resolve_temperature(temperature),
            "stream": True,
        }
        _apply_reasoning_controls(
            payload,
            provider=provider,
            base_url=base_url,
            model=model,
            reasoning_effort=str(
                self.llm_config.get("reasoning_effort", "off")
            ).strip().lower(),
        )
        if provider == "openai":
            payload["stream_options"] = {"include_usage": True}
        resolved_max_tokens = self._resolve_max_tokens(max_tokens)
        if resolved_max_tokens:
            payload["max_tokens"] = resolved_max_tokens

        content_parts: list[str] = []
        response_model = model
        finish_reason = ""
        usage: Dict[str, Any] = {}
        stream_metadata: Dict[str, Any] = {"stream": True}

        def consume(data: Dict[str, Any]) -> None:
            nonlocal response_model, finish_reason, usage
            if isinstance(data.get("error"), dict):
                error_payload = dict(data.get("error", {}) or {})
                raise LLMRequestError(
                    str(error_payload.get("message", "OpenAI-compatible stream failed."))
                )
            if str(data.get("model", "")).strip():
                response_model = str(data.get("model", "")).strip()
            event_usage = data.get("usage", {})
            if isinstance(event_usage, dict) and event_usage:
                usage.update(event_usage)
            choices = data.get("choices", [])
            first = choices[0] if isinstance(choices, list) and choices else {}
            if not isinstance(first, dict):
                return
            delta_payload = first.get("delta", {})
            if isinstance(delta_payload, dict):
                delta = self._extract_stream_text(delta_payload.get("content", ""))
                reasoning_delta = self._extract_stream_text(
                    delta_payload.get("reasoning_content", "")
                    or delta_payload.get("reasoning", "")
                )
            else:
                delta = ""
                reasoning_delta = ""
            reasoning_callback = getattr(on_delta, "on_reasoning", None)
            if reasoning_delta and callable(reasoning_callback):
                reasoning_callback(reasoning_delta)
            if delta:
                content_parts.append(delta)
                if callable(on_delta):
                    on_delta(delta)
            event_finish_reason = str(first.get("finish_reason", "") or "").strip()
            if event_finish_reason:
                finish_reason = event_finish_reason

        event_count = self._post_json_stream(
            url=self._endpoint(base_url, "/chat/completions"),
            payload=payload,
            headers={
                "Authorization": f"Bearer {api_key}",
                "Accept": "text/event-stream",
            },
            consume=consume,
            sse=True,
            is_complete=lambda: bool(finish_reason),
        )
        stream_metadata.update(
            {
                "model": response_model,
                "usage": usage,
                "event_count": event_count,
            }
        )
        prompt_tokens = _non_negative_int(usage.get("prompt_tokens"))
        completion_tokens = _non_negative_int(usage.get("completion_tokens"))
        return {
            "content": "".join(content_parts),
            "model": response_model,
            "finish_reason": finish_reason,
            "prompt_tokens": prompt_tokens,
            "completion_tokens": completion_tokens,
            "cache_usage": normalize_cache_usage(
                stream_metadata, prompt_tokens=prompt_tokens
            ),
            "raw": stream_metadata,
        }

    def _chat_anthropic(
        self,
        *,
        messages: List[Dict[str, Any]],
        model: str,
        temperature: Optional[float],
        max_tokens: Optional[int],
    ) -> Dict[str, Any]:
        api_key = self._resolve_api_key("anthropic")
        base_url = self._resolve_base_url("anthropic")
        system_parts: List[tuple[str, bool]] = []
        chat_messages: List[Dict[str, str]] = []
        for item in messages:
            role = str(item.get("role", "user")).strip()
            content = str(item.get("content", "")).strip()
            if not content:
                continue
            if role == "system":
                system_parts.append((content, bool(item.get("cache_static", False))))
            else:
                chat_messages.append(
                    {
                        "role": "assistant" if role == "assistant" else "user",
                        "content": content,
                    }
                )
        payload: Dict[str, Any] = {
            "model": model,
            "messages": chat_messages,
            "temperature": self._resolve_temperature(temperature),
            "max_tokens": self._resolve_max_tokens(max_tokens, default=512),
        }
        if system_parts:
            if any(cache_static for _, cache_static in system_parts):
                blocks: List[Dict[str, Any]] = []
                for index, (content, cache_static) in enumerate(system_parts):
                    block: Dict[str, Any] = {
                        "type": "text",
                        "text": content
                        + ("\n\n" if index < len(system_parts) - 1 else ""),
                    }
                    if cache_static:
                        block["cache_control"] = {"type": "ephemeral"}
                    blocks.append(block)
                payload["system"] = blocks
            else:
                payload["system"] = "\n\n".join(
                    content for content, _ in system_parts
                )

        data = self._post_json(
            url=self._endpoint(base_url, "/messages"),
            payload=payload,
            headers={
                "x-api-key": api_key,
                "anthropic-version": "2023-06-01",
            },
        )
        content_blocks = data.get("content", [])
        content = ""
        for block in content_blocks:
            if isinstance(block, dict) and block.get("type") == "text":
                content += str(block.get("text", ""))
        usage = data.get("usage", {})
        return {
            "content": content.strip(),
            "model": data.get("model", model),
            "finish_reason": str(data.get("stop_reason", "")).strip(),
            "prompt_tokens": int(usage.get("input_tokens", 0)),
            "completion_tokens": int(usage.get("output_tokens", 0)),
            "cache_usage": normalize_cache_usage(
                data, prompt_tokens=_non_negative_int(usage.get("input_tokens"))
            ),
            "raw": data,
        }

    def _chat_anthropic_stream(
        self,
        *,
        messages: List[Dict[str, Any]],
        model: str,
        temperature: Optional[float],
        max_tokens: Optional[int],
        on_delta: Callable[[str], None] | None,
    ) -> Dict[str, Any]:
        api_key = self._resolve_api_key("anthropic")
        base_url = self._resolve_base_url("anthropic")
        system_parts: List[tuple[str, bool]] = []
        chat_messages: List[Dict[str, str]] = []
        for item in messages:
            role = str(item.get("role", "user")).strip()
            content = str(item.get("content", "")).strip()
            if not content:
                continue
            if role == "system":
                system_parts.append((content, bool(item.get("cache_static", False))))
            else:
                chat_messages.append(
                    {
                        "role": "assistant" if role == "assistant" else "user",
                        "content": content,
                    }
                )
        payload: Dict[str, Any] = {
            "model": model,
            "messages": chat_messages,
            "temperature": self._resolve_temperature(temperature),
            "max_tokens": self._resolve_max_tokens(max_tokens, default=512),
            "stream": True,
        }
        if system_parts:
            if any(cache_static for _, cache_static in system_parts):
                blocks: List[Dict[str, Any]] = []
                for index, (content, cache_static) in enumerate(system_parts):
                    block: Dict[str, Any] = {
                        "type": "text",
                        "text": content
                        + ("\n\n" if index < len(system_parts) - 1 else ""),
                    }
                    if cache_static:
                        block["cache_control"] = {"type": "ephemeral"}
                    blocks.append(block)
                payload["system"] = blocks
            else:
                payload["system"] = "\n\n".join(
                    content for content, _ in system_parts
                )

        content_parts: list[str] = []
        response_model = model
        finish_reason = ""
        usage: Dict[str, Any] = {}
        saw_message_stop = False

        def consume(data: Dict[str, Any]) -> None:
            nonlocal response_model, finish_reason, saw_message_stop
            event_type = str(data.get("type", "")).strip()
            if event_type == "error" or isinstance(data.get("error"), dict):
                error_payload = dict(data.get("error", {}) or {})
                raise LLMRequestError(
                    str(error_payload.get("message", "Anthropic stream failed."))
                )
            if event_type == "message_start":
                message_payload = dict(data.get("message", {}) or {})
                if str(message_payload.get("model", "")).strip():
                    response_model = str(message_payload.get("model", "")).strip()
                start_usage = message_payload.get("usage", {})
                if isinstance(start_usage, dict):
                    usage.update(start_usage)
                return
            if event_type == "content_block_start":
                block = dict(data.get("content_block", {}) or {})
                delta = (
                    str(block.get("text", ""))
                    if str(block.get("type", "")).strip() == "text"
                    else ""
                )
            elif event_type == "content_block_delta":
                delta_payload = dict(data.get("delta", {}) or {})
                delta = (
                    str(delta_payload.get("text", ""))
                    if str(delta_payload.get("type", "")).strip() == "text_delta"
                    else ""
                )
            else:
                delta = ""
            if delta:
                content_parts.append(delta)
                if callable(on_delta):
                    on_delta(delta)
            if event_type == "message_delta":
                delta_payload = dict(data.get("delta", {}) or {})
                event_finish_reason = str(
                    delta_payload.get("stop_reason", "") or ""
                ).strip()
                if event_finish_reason:
                    finish_reason = event_finish_reason
                delta_usage = data.get("usage", {})
                if isinstance(delta_usage, dict):
                    usage.update(delta_usage)
            if event_type == "message_stop":
                saw_message_stop = True
                finish_reason = finish_reason or "stop"

        event_count = self._post_json_stream(
            url=self._endpoint(base_url, "/messages"),
            payload=payload,
            headers={
                "x-api-key": api_key,
                "anthropic-version": "2023-06-01",
                "Accept": "text/event-stream",
            },
            consume=consume,
            sse=True,
            is_complete=lambda: saw_message_stop,
        )
        raw = {
            "stream": True,
            "model": response_model,
            "usage": usage,
            "event_count": event_count,
        }
        prompt_tokens = _non_negative_int(usage.get("input_tokens"))
        completion_tokens = _non_negative_int(usage.get("output_tokens"))
        return {
            "content": "".join(content_parts).strip(),
            "model": response_model,
            "finish_reason": finish_reason,
            "prompt_tokens": prompt_tokens,
            "completion_tokens": completion_tokens,
            "cache_usage": normalize_cache_usage(
                raw, prompt_tokens=prompt_tokens
            ),
            "raw": raw,
        }

    def _chat_ollama(
        self,
        *,
        messages: List[Dict[str, Any]],
        model: str,
        temperature: Optional[float],
        max_tokens: Optional[int],
    ) -> Dict[str, Any]:
        base_url = self._resolve_base_url("ollama")
        payload: Dict[str, Any] = {
            "model": model,
            "messages": strip_cache_static_markers(messages),
            "stream": False,
            "options": {
                "temperature": self._resolve_temperature(temperature),
            },
        }
        resolved_max_tokens = self._resolve_max_tokens(max_tokens)
        if resolved_max_tokens:
            payload["options"]["num_predict"] = resolved_max_tokens

        data = self._post_json(
            url=self._endpoint(base_url, "/api/chat"),
            payload=payload,
        )
        message = (
            data.get("message", {}) if isinstance(data.get("message", {}), dict) else {}
        )
        prompt_tokens = int(data.get("prompt_eval_count", 0))
        completion_tokens = int(data.get("eval_count", 0))
        return {
            "content": self._extract_text_content(message),
            "model": data.get("model", model),
            "finish_reason": str(data.get("done_reason", "")).strip(),
            "prompt_tokens": prompt_tokens,
            "completion_tokens": completion_tokens,
            "cache_usage": normalize_cache_usage(
                data, prompt_tokens=prompt_tokens
            ),
            "raw": data,
        }

    def _chat_ollama_stream(
        self,
        *,
        messages: List[Dict[str, Any]],
        model: str,
        temperature: Optional[float],
        max_tokens: Optional[int],
        on_delta: Callable[[str], None] | None,
    ) -> Dict[str, Any]:
        base_url = self._resolve_base_url("ollama")
        payload: Dict[str, Any] = {
            "model": model,
            "messages": strip_cache_static_markers(messages),
            "stream": True,
            "options": {
                "temperature": self._resolve_temperature(temperature),
            },
        }
        resolved_max_tokens = self._resolve_max_tokens(max_tokens)
        if resolved_max_tokens:
            payload["options"]["num_predict"] = resolved_max_tokens

        content_parts: list[str] = []
        response_model = model
        finish_reason = ""
        final_payload: Dict[str, Any] = {}

        def consume(data: Dict[str, Any]) -> None:
            nonlocal response_model, finish_reason, final_payload
            if data.get("error"):
                raise LLMRequestError(str(data.get("error")))
            if str(data.get("model", "")).strip():
                response_model = str(data.get("model", "")).strip()
            message_payload = data.get("message", {})
            delta = (
                self._extract_stream_text(message_payload.get("content", ""))
                if isinstance(message_payload, dict)
                else ""
            )
            if delta:
                content_parts.append(delta)
                if callable(on_delta):
                    on_delta(delta)
            if bool(data.get("done")):
                final_payload = dict(data)
                finish_reason = str(data.get("done_reason", "") or "").strip()

        event_count = self._post_json_stream(
            url=self._endpoint(base_url, "/api/chat"),
            payload=payload,
            consume=consume,
            sse=False,
            is_complete=lambda: bool(final_payload.get("done")),
        )
        raw = {
            **final_payload,
            "stream": True,
            "event_count": event_count,
        }
        prompt_tokens = _non_negative_int(final_payload.get("prompt_eval_count"))
        completion_tokens = _non_negative_int(final_payload.get("eval_count"))
        return {
            "content": "".join(content_parts),
            "model": response_model,
            "finish_reason": finish_reason,
            "prompt_tokens": prompt_tokens,
            "completion_tokens": completion_tokens,
            "cache_usage": normalize_cache_usage(
                raw, prompt_tokens=prompt_tokens
            ),
            "raw": raw,
        }

    @staticmethod
    def _extract_text_content(value: Any) -> str:
        if isinstance(value, str):
            return value.strip()
        if isinstance(value, list):
            parts: list[str] = []
            for item in value:
                if isinstance(item, str):
                    text = item.strip()
                    if text:
                        parts.append(text)
                elif isinstance(item, dict):
                    text = str(item.get("text", "") or item.get("content", "")).strip()
                    if text:
                        parts.append(text)
            return "\n".join(parts).strip()
        if isinstance(value, dict):
            for key in ("content", "text", "output_text"):
                text = value.get(key)
                if isinstance(text, str) and text.strip():
                    return text.strip()
                if isinstance(text, list):
                    nested = LLMClient._extract_text_content(text)
                    if nested:
                        return nested
            for key in ("reasoning_content", "reasoning"):
                text = value.get(key)
                if isinstance(text, str) and text.strip():
                    return text.strip()
            return ""
        return str(value or "").strip()

    @staticmethod
    def _extract_stream_text(value: Any) -> str:
        """Extract content without trimming chunk boundary whitespace."""

        if isinstance(value, str):
            return value
        if isinstance(value, list):
            parts: list[str] = []
            for item in value:
                if isinstance(item, str):
                    parts.append(item)
                elif isinstance(item, dict):
                    parts.append(LLMClient._extract_stream_text(item))
            return "".join(parts)
        if isinstance(value, dict):
            for key in ("content", "text", "output_text"):
                if key in value:
                    return LLMClient._extract_stream_text(value.get(key))
            return ""
        return ""

    def _chat_host_bridge(
        self,
        *,
        messages: List[Dict[str, Any]],
        model: str,
        temperature: Optional[float],
        max_tokens: Optional[int],
    ) -> Dict[str, Any]:
        url = self._resolve_host_bridge_url()
        payload: Dict[str, Any] = {
            "messages": strip_cache_static_markers(messages),
            "model": model,
            "temperature": self._resolve_temperature(temperature),
            "max_tokens": self._resolve_max_tokens(max_tokens, default=512),
            "provider": "host-bridge",
            "metadata": {
                "source": "zaomeng",
                "configured_provider": str(
                    self.llm_config.get("provider", self.LOCAL_PROVIDER)
                )
                .strip()
                .lower(),
            },
        }
        headers: Dict[str, str] = {}
        token = self._resolve_host_bridge_token()
        if token:
            headers["Authorization"] = f"Bearer {token}"
            headers["X-Zaomeng-Bridge-Token"] = token
        data = self._post_json(url=url, payload=payload, headers=headers or None)
        return self._normalize_host_bridge_response(data, fallback_model=model)

    def _post_json(
        self,
        *,
        url: str,
        payload: Dict[str, Any],
        headers: Optional[Dict[str, str]] = None,
    ) -> Dict[str, Any]:
        request_headers = {
            "Content-Type": "application/json",
        }
        if headers:
            request_headers.update(headers)

        timeout = float(self.llm_config.get("timeout_seconds", 120) or 120)
        attempts = self._retry_attempts()
        retry_status_codes = self._retry_status_codes()
        last_error: Optional[Exception] = None

        for attempt in range(1, attempts + 1):
            try:
                with requests.post(
                    url,
                    json=payload,
                    headers=request_headers,
                    timeout=timeout,
                ) as resp:
                    body_text = resp.text
                    if resp.status_code >= 400:
                        raise requests.HTTPError(
                            f"{resp.status_code} {resp.reason}", response=resp
                        )
                    return json.loads(body_text)
            except requests.HTTPError as exc:
                response = exc.response
                status_code = int(response.status_code) if response is not None else 0
                reason = str(response.reason) if response is not None else str(exc)
                body_text = response.text if response is not None else ""
                last_error = exc
                if attempt < attempts and status_code in retry_status_codes:
                    self._sleep_before_retry(attempt, f"HTTP {status_code} {reason}")
                    continue
                raise LLMRequestError(
                    f"LLM 请求失败: {status_code} {reason} | {body_text}"
                ) from exc
            except requests.RequestException as exc:
                last_error = exc
                if attempt < attempts:
                    self._sleep_before_retry(attempt, f"connection error: {exc}")
                    continue
                raise LLMRequestError(f"LLM 连接失败: {exc}") from exc
            except OSError as exc:
                last_error = exc
                if attempt < attempts:
                    self._sleep_before_retry(attempt, f"socket error: {exc}")
                    continue
                raise LLMRequestError(f"LLM 连接失败: {exc}") from exc
            except json.JSONDecodeError as exc:
                raise LLMRequestError("LLM 返回了无法解析的 JSON 响应") from exc

        raise LLMRequestError(f"LLM 请求失败: {last_error}") from last_error

    def _post_json_stream(
        self,
        *,
        url: str,
        payload: Dict[str, Any],
        consume: Callable[[Dict[str, Any]], None],
        headers: Optional[Dict[str, str]] = None,
        sse: bool,
        is_complete: Callable[[], bool] | None = None,
    ) -> int:
        """Consume an SSE or NDJSON response and return its JSON event count.

        A connection may be retried only before the first provider event. Retrying
        after content starts would splice a second model generation onto the first.
        """

        request_headers = {
            "Content-Type": "application/json",
        }
        if headers:
            request_headers.update(headers)

        timeout = float(self.llm_config.get("timeout_seconds", 120) or 120)
        total_timeout = float(
            self.llm_config.get("stream_total_timeout_seconds", 600) or 600
        )
        attempts = self._retry_attempts()
        retry_status_codes = self._retry_status_codes()
        last_error: Optional[Exception] = None
        event_count = 0

        for attempt in range(1, attempts + 1):
            saw_event = False
            attempt_started_at = time.monotonic()
            try:
                with requests.post(
                    url,
                    json=payload,
                    headers=request_headers,
                    timeout=timeout,
                    stream=True,
                ) as resp:
                    if resp.status_code >= 400:
                        raise requests.HTTPError(
                            f"{resp.status_code} {resp.reason}", response=resp
                        )
                    # The requests default (512 bytes) noticeably delays short
                    # reasoning deltas, so consume SSE/NDJSON as soon as bytes arrive.
                    for raw_line in resp.iter_lines(chunk_size=1, decode_unicode=False):
                        if time.monotonic() - attempt_started_at > total_timeout:
                            raise LLMRequestError(
                                f"LLM 流式响应超过 {total_timeout:g} 秒总时限。"
                            )
                        if isinstance(raw_line, bytes):
                            line = raw_line.decode("utf-8-sig", errors="replace")
                        else:
                            line = str(raw_line)
                        line = line.rstrip("\r\n")
                        if not line:
                            continue
                        data_text = line
                        if sse:
                            if line.startswith(":") or line.startswith(
                                ("event:", "id:", "retry:")
                            ):
                                continue
                            if not line.startswith("data:"):
                                continue
                            data_text = line[5:].lstrip()
                            if not data_text:
                                continue
                        if data_text == "[DONE]":
                            return event_count
                        data = json.loads(data_text)
                        if not isinstance(data, dict):
                            raise LLMRequestError(
                                "LLM stream event must be a JSON object."
                            )
                        saw_event = True
                        event_count += 1
                        consume(data)
                    if callable(is_complete) and not is_complete():
                        if not saw_event and attempt < attempts:
                            self._sleep_before_retry(
                                attempt, "stream ended before its completion event"
                            )
                            continue
                        raise LLMRequestError(
                            "LLM stream ended before its completion event."
                        )
                    return event_count
            except requests.HTTPError as exc:
                response = exc.response
                status_code = int(response.status_code) if response is not None else 0
                reason = str(response.reason) if response is not None else str(exc)
                body_text = response.text if response is not None else ""
                last_error = exc
                if (
                    not saw_event
                    and attempt < attempts
                    and status_code in retry_status_codes
                ):
                    self._sleep_before_retry(
                        attempt, f"HTTP {status_code} {reason}"
                    )
                    continue
                raise LLMRequestError(
                    f"LLM 请求失败: {status_code} {reason} | {body_text}"
                ) from exc
            except requests.RequestException as exc:
                last_error = exc
                if not saw_event and attempt < attempts:
                    self._sleep_before_retry(
                        attempt, f"connection error: {exc}"
                    )
                    continue
                raise LLMRequestError(f"LLM 连接失败: {exc}") from exc
            except OSError as exc:
                last_error = exc
                if not saw_event and attempt < attempts:
                    self._sleep_before_retry(attempt, f"socket error: {exc}")
                    continue
                raise LLMRequestError(f"LLM 连接失败: {exc}") from exc
            except json.JSONDecodeError as exc:
                raise LLMRequestError(
                    "LLM stream returned an invalid JSON event."
                ) from exc

        raise LLMRequestError(f"LLM 请求失败: {last_error}") from last_error

    def _retry_attempts(self) -> int:
        return max(1, int(self.llm_config.get("retry_attempts", 3) or 1))

    def _retry_backoff_seconds(self) -> float:
        return max(0.0, float(self.llm_config.get("retry_backoff_seconds", 1.0) or 0.0))

    def _retry_backoff_multiplier(self) -> float:
        return max(
            1.0, float(self.llm_config.get("retry_backoff_multiplier", 2.0) or 1.0)
        )

    def _retry_status_codes(self) -> set[int]:
        configured = self.llm_config.get(
            "retry_status_codes", self.DEFAULT_RETRY_STATUS_CODES
        )
        if not isinstance(configured, (list, tuple, set)):
            configured = self.DEFAULT_RETRY_STATUS_CODES
        return {int(code) for code in configured}

    def _sleep_before_retry(self, attempt: int, reason: str) -> None:
        delay = self._retry_backoff_seconds() * (
            self._retry_backoff_multiplier() ** (attempt - 1)
        )
        if delay <= 0:
            return
        logger.warning(
            "LLM request retry %s/%s after %s (sleep %.2fs)",
            attempt,
            self._retry_attempts() - 1,
            reason,
            delay,
        )
        time.sleep(delay)

    def _resolve_api_key(self, provider: str) -> str:
        configured = str(self.llm_config.get("api_key", "")).strip()
        if configured:
            return configured

        explicit_env = str(self.llm_config.get("api_key_env", "")).strip()
        if explicit_env and os.getenv(explicit_env):
            return str(os.getenv(explicit_env, "")).strip()

        fallback_envs = {
            "openai": ("OPENAI_API_KEY",),
            "openai-compatible": ("OPENAI_API_KEY",),
            "anthropic": ("ANTHROPIC_API_KEY",),
        }
        for env_name in fallback_envs.get(provider, ()):
            value = str(os.getenv(env_name, "")).strip()
            if value:
                return value

        raise MissingAPIKeyError(
            f"{provider} provider 缺少 API key，请在 config.yaml 或环境变量中配置。"
        )

    def _resolve_base_url(self, provider: str) -> str:
        configured = str(self.llm_config.get("base_url", "")).strip()
        if configured:
            return configured.rstrip("/")

        defaults = {
            "openai": self.DEFAULT_OPENAI_BASE_URL,
            "openai-compatible": self.DEFAULT_OPENAI_BASE_URL,
            "anthropic": self.DEFAULT_ANTHROPIC_BASE_URL,
            "ollama": self.DEFAULT_OLLAMA_BASE_URL,
        }
        return defaults.get(provider, self.DEFAULT_OPENAI_BASE_URL)

    def _host_bridge_url(self) -> str:
        configured = str(self.llm_config.get("host_bridge_url", "")).strip()
        if configured:
            return configured
        configured_base = str(self.llm_config.get("base_url", "")).strip()
        if (
            str(self.llm_config.get("provider", "")).strip().lower() == "host-bridge"
            and configured_base
        ):
            return configured_base
        return str(os.getenv(self.HOST_BRIDGE_ENV_URL, "")).strip()

    def _resolve_host_bridge_url(self) -> str:
        configured = self._host_bridge_url()
        if not configured:
            raise LLMRequestError(
                "host-bridge provider 已启用，但未提供 bridge URL。请配置 llm.host_bridge_url 或环境变量 "
                f"{self.HOST_BRIDGE_ENV_URL}。"
            )
        if "://" not in configured:
            configured = f"http://{configured.lstrip('/')}"
        if configured.endswith(
            ("/chat/completions", "/api/chat", "/v1/chat/completions")
        ):
            return configured
        return configured.rstrip("/") + self.DEFAULT_HOST_BRIDGE_PATH

    def _resolve_host_bridge_token(self) -> str:
        configured = str(self.llm_config.get("host_bridge_token", "")).strip()
        if configured:
            return configured
        explicit_env = str(self.llm_config.get("host_bridge_token_env", "")).strip()
        if explicit_env and os.getenv(explicit_env):
            return str(os.getenv(explicit_env, "")).strip()
        return str(os.getenv(self.HOST_BRIDGE_ENV_TOKEN, "")).strip()

    def _resolve_temperature(self, temperature: Optional[float]) -> float:
        if temperature is not None:
            return float(temperature)
        return float(self.llm_config.get("temperature", 0.2) or 0.2)

    def _resolve_max_tokens(self, max_tokens: Optional[int], default: int = 0) -> int:
        if max_tokens is not None:
            return int(max_tokens)
        configured = int(self.llm_config.get("max_tokens", default) or default)
        return configured

    def _normalize_host_bridge_response(
        self, data: Dict[str, Any], fallback_model: str
    ) -> Dict[str, Any]:
        content = ""
        choices = data.get("choices", [])
        first = choices[0] if isinstance(choices, list) and choices else {}
        if isinstance(data.get("content"), str):
            content = str(data.get("content", "")).strip()
        elif isinstance(data.get("message"), dict):
            content = str(data.get("message", {}).get("content", "")).strip()
        else:
            if isinstance(first, dict):
                message = first.get("message", {})
                if isinstance(message, dict):
                    content = str(message.get("content", "")).strip()

        usage = data.get("usage", {}) if isinstance(data.get("usage", {}), dict) else {}
        return {
            "content": content,
            "model": str(data.get("model", fallback_model)).strip() or fallback_model,
            "finish_reason": str(
                data.get(
                    "finish_reason",
                    first.get("finish_reason", "") if isinstance(first, dict) else "",
                )
            ).strip(),
            "prompt_tokens": int(
                data.get("prompt_tokens", usage.get("prompt_tokens", 0)) or 0
            ),
            "completion_tokens": int(
                data.get("completion_tokens", usage.get("completion_tokens", 0)) or 0
            ),
            "cache_usage": normalize_cache_usage(
                data,
                prompt_tokens=_non_negative_int(
                    data.get("prompt_tokens", usage.get("prompt_tokens", 0))
                ),
            ),
            "raw": data,
        }

    @staticmethod
    def _endpoint(base_url: str, suffix: str) -> str:
        if base_url.endswith(suffix):
            return base_url
        return f"{base_url.rstrip('/')}{suffix}"

    def get_cost_summary(self) -> Dict[str, Any]:
        daily_budget = float(self.cost_config.get("daily_budget_usd", 10.0))
        remaining_budget = max(0.0, daily_budget - self.daily_cost)
        return {
            "session_cost": self.session_cost,
            "daily_cost": self.daily_cost,
            "daily_budget": daily_budget,
            "remaining_budget": remaining_budget,
            "budget_usage_percent": (
                (self.daily_cost / daily_budget * 100) if daily_budget > 0 else 0
            ),
            "request_count": self.request_count,
            "total_tokens": self.total_tokens,
            "provider": self.provider_name(),
        }

    def reset_session_cost(self):
        self.session_cost = 0.0
        logger.info("会话成本统计已重置")
