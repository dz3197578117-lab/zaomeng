#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
造梦轻量版 · 角色对话引擎（后端，纯 Python 标准库，零第三方依赖）

把 wkbin/zaomeng 的核心玩法 + 多模型接入改进落成一个可立即运行的 Web 应用：
  - 选 Provider（含商汤 SenseTime / DeepSeek / 通义 / Kimi / 智谱 ... 共 15+ 家）
  - 按接口动态拉取模型清单（GET {baseUrl}/models，失败回退预设）
  - 配置角色卡（名字 / 人设 / 关系）
  - 多角色 NDJSON 流式对话（OpenAI 兼容端点真实流式；Anthropic/Gemini/Azure 非流式兜底）
  - 内置 mock 模式：无 API Key 也能体验完整流程

运行：
  python server.py            # 默认 http://127.0.0.1:8000
  python server.py 9000       # 指定端口

接口：
  GET  /                      前端页面
  GET  /api/providers         返回 Provider 预设目录
  POST /api/models            {provider_id, base_url, api_key} -> 模型清单
  POST /api/chat              流式 SSE：{provider_id, base_url, api_key, model, characters, scene, history, user_input}
"""

import json
import sys
import urllib.request
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ----------------------------------------------------------------------------
# Provider 预设目录（移植自 zaomeng-provider-presets.kt，含商汤）
# kind: openai | anthropic | gemini | azure | mock
# ----------------------------------------------------------------------------
PRESETS = [
    {
        "id": "mock", "label": "演示模式（无需 Key）", "kind": "mock",
        "default_base_url": "", "auth": "none", "api_key_hint": "演示模式无需 Key",
        "capabilities": {"streaming": True, "tools": False, "vision": False, "reasoning": False, "structuredOutput": False},
        "models": [
            {"id": "mock-char", "label": "演示角色模型（合成数据）"},
        ],
        "notes": "离线演示，使用内置脚本化多角色对话，便于先体验 UI 与协议。",
    },
    # ---------------- 国内 OpenAI 兼容 ----------------
    {
        "id": "sensetime", "label": "商汤 SenseTime · 日日新 SenseNova", "kind": "openai",
        "default_base_url": "https://api.sensenova.cn/compatible-mode/v2", "auth": "bearer",
        "api_key_hint": "sk-...（SenseNova 控制台 API Key）",
        "capabilities": {"streaming": True, "tools": True, "vision": True, "reasoning": True, "structuredOutput": False},
        "models": [
            {"id": "SenseChat-5", "label": "SenseChat-5（V5 旗舰，128K）"},
            {"id": "SenseChat-Turbo", "label": "SenseChat-Turbo（高速低成本）"},
            {"id": "SenseNova-V6.5-Pro", "label": "SenseNova-V6.5-Pro（多模态 128K）", "note": "含视觉"},
            {"id": "SenseChat-Vision", "label": "SenseChat-Vision（图文感知）", "note": "视觉"},
            {"id": "SenseChat-Character-Pro", "label": "SenseChat-Character-Pro（拟人对话·角色扮演，32K）", "note": "推荐用于造梦"},
            {"id": "SenseChat-Character", "label": "SenseChat-Character（角色对话标准版，8K）", "note": "推荐用于造梦"},
        ],
        "notes": "OpenAI 兼容模式 baseUrl 带 /compatible-mode/v2；推理模型会先吐 reasoning 增量。自带 Character 模型，契合角色扮演。",
    },
    {
        "id": "deepseek", "label": "DeepSeek", "kind": "openai",
        "default_base_url": "https://api.deepseek.com/v1", "auth": "bearer",
        "api_key_hint": "sk-...（DeepSeek 平台 Key）",
        "capabilities": {"streaming": True, "tools": True, "vision": False, "reasoning": True, "structuredOutput": False},
        "models": [
            {"id": "deepseek-chat", "label": "DeepSeek-V3（deepseek-chat）"},
            {"id": "deepseek-reasoner", "label": "DeepSeek-R1（deepseek-reasoner，推理）", "note": "推理"},
        ],
    },
    {
        "id": "siliconflow", "label": "硅基流动 SiliconFlow", "kind": "openai",
        "default_base_url": "https://api.siliconflow.cn/v1", "auth": "bearer",
        "api_key_hint": "sk-...（SiliconFlow API Key）",
        "capabilities": {"streaming": True, "tools": False, "vision": False, "reasoning": True, "structuredOutput": False},
        "models": [
            {"id": "deepseek-ai/DeepSeek-V3", "label": "DeepSeek-V3"},
            {"id": "deepseek-ai/DeepSeek-R1", "label": "DeepSeek-R1（推理）", "note": "推理"},
            {"id": "Qwen/Qwen2.5-72B-Instruct", "label": "Qwen2.5-72B"},
            {"id": "THUDM/glm-4-9b-chat", "label": "GLM-4-9B"},
        ],
    },
    {
        "id": "qwen", "label": "阿里通义千问 Qwen", "kind": "openai",
        "default_base_url": "https://dashscope.aliyuncs.com/compatible-mode/v1", "auth": "bearer",
        "api_key_hint": "sk-...（DashScope API Key）",
        "capabilities": {"streaming": True, "tools": True, "vision": True, "structuredOutput": False},
        "models": [
            {"id": "qwen-max", "label": "通义千问-Max"},
            {"id": "qwen-plus", "label": "通义千问-Plus"},
            {"id": "qwen-turbo", "label": "通义千问-Turbo（低成本）"},
            {"id": "qwen-long", "label": "通义千问-Long（长上下文）"},
            {"id": "qwen-vl-max", "label": "Qwen-VL-Max（视觉）", "note": "视觉"},
        ],
    },
    {
        "id": "moonshot", "label": "月之暗面 Kimi（Moonshot）", "kind": "openai",
        "default_base_url": "https://api.moonshot.cn/v1", "auth": "bearer",
        "api_key_hint": "sk-...（Moonshot API Key）",
        "capabilities": {"streaming": True, "tools": True, "vision": False, "structuredOutput": False},
        "models": [
            {"id": "moonshot-v1-8k", "label": "Kimi 8K"},
            {"id": "moonshot-v1-32k", "label": "Kimi 32K"},
            {"id": "moonshot-v1-128k", "label": "Kimi 128K（长上下文）"},
            {"id": "kimi-k2-0711-preview", "label": "Kimi-K2（Agent/工具）", "note": "支持工具"},
        ],
    },
    {
        "id": "zhipu", "label": "智谱 Zhipu · GLM", "kind": "openai",
        "default_base_url": "https://open.bigmodel.cn/api/paas/v4", "auth": "bearer",
        "api_key_hint": "sk-...（智谱开放平台 Key）",
        "capabilities": {"streaming": True, "tools": True, "vision": True, "structuredOutput": False},
        "models": [
            {"id": "glm-4-plus", "label": "GLM-4-Plus"},
            {"id": "glm-4-air", "label": "GLM-4-Air（低成本）"},
            {"id": "glm-4-flash", "label": "GLM-4-Flash（免费档）"},
            {"id": "glm-4v", "label": "GLM-4V（视觉）", "note": "视觉"},
        ],
    },
    {
        "id": "stepfun", "label": "阶跃 StepFun", "kind": "openai",
        "default_base_url": "https://api.stepfun.com/v1", "auth": "bearer",
        "api_key_hint": "sk-...（阶跃 API Key）",
        "capabilities": {"streaming": True, "tools": True, "vision": True, "structuredOutput": False},
        "models": [
            {"id": "step-1.5", "label": "Step-1.5（待核实）"},
            {"id": "step-1v", "label": "Step-1V（视觉，待核实）", "note": "视觉"},
        ],
        "notes": "模型 ID 与可用性请以来台阶跃官方文档核实。",
    },
    {
        "id": "minimax", "label": "MiniMax", "kind": "openai",
        "default_base_url": "https://api.minimax.chat/v1", "auth": "bearer",
        "api_key_hint": "sk-...（MiniMax API Key；另需 GroupId）",
        "capabilities": {"streaming": True, "tools": True, "vision": False, "structuredOutput": False},
        "models": [
            {"id": "MiniMax-Text-01", "label": "MiniMax-Text-01"},
            {"id": "abab6.5-chat", "label": "abab6.5-chat"},
        ],
        "notes": "MiniMax 还需 GroupId 参数，接入层需额外透传（当前以 base_url 手动追加）。",
    },
    # ---------------- 海外 ----------------
    {
        "id": "openai", "label": "OpenAI", "kind": "openai",
        "default_base_url": "https://api.openai.com/v1", "auth": "bearer",
        "api_key_hint": "sk-...（OpenAI API Key）",
        "capabilities": {"streaming": True, "tools": True, "vision": True, "reasoning": True, "structuredOutput": True},
        "models": [
            {"id": "gpt-4o", "label": "GPT-4o（视觉）", "note": "视觉"},
            {"id": "gpt-4o-mini", "label": "GPT-4o-mini（低成本）"},
            {"id": "o1", "label": "o1（推理）", "note": "推理"},
            {"id": "o3-mini", "label": "o3-mini（推理）", "note": "推理"},
        ],
    },
    {
        "id": "anthropic", "label": "Anthropic Claude", "kind": "anthropic",
        "default_base_url": "https://api.anthropic.com/v1", "auth": "anthropic",
        "api_key_hint": "sk-ant-...（Anthropic API Key）",
        "capabilities": {"streaming": True, "tools": True, "vision": True, "reasoning": False, "structuredOutput": False},
        "models": [
            {"id": "claude-3-5-sonnet-latest", "label": "Claude 3.5 Sonnet"},
            {"id": "claude-3-5-haiku-latest", "label": "Claude 3.5 Haiku（低成本）"},
            {"id": "claude-3-opus-latest", "label": "Claude 3 Opus"},
        ],
        "notes": "非流式兜底（形态与 OpenAI 不同，真实流式需适配层）。",
    },
    {
        "id": "gemini", "label": "Google Gemini", "kind": "gemini",
        "default_base_url": "https://generativelanguage.googleapis.com/v1beta", "auth": "gemini",
        "api_key_hint": "AIza...（Gemini API Key）",
        "capabilities": {"streaming": True, "tools": True, "vision": True, "reasoning": True, "structuredOutput": False},
        "models": [
            {"id": "gemini-1.5-pro", "label": "Gemini 1.5 Pro"},
            {"id": "gemini-1.5-flash", "label": "Gemini 1.5 Flash（低成本）"},
            {"id": "gemini-2.0-flash", "label": "Gemini 2.0 Flash"},
        ],
        "notes": "非流式兜底（generativelanguage API 形态不同）。",
    },
    {
        "id": "azure", "label": "Azure OpenAI", "kind": "azure",
        "default_base_url": "https://<your-resource>.openai.azure.com/openai", "auth": "azure",
        "api_key_hint": "Azure OpenAI Key（另需 api-version / deployment）",
        "capabilities": {"streaming": True, "tools": True, "vision": True, "reasoning": True, "structuredOutput": True},
        "models": [
            {"id": "gpt-4o", "label": "GPT-4o（deployment 名）"},
            {"id": "gpt-4o-mini", "label": "GPT-4o-mini（deployment 名）"},
        ],
        "notes": "baseUrl 中 <your-resource> 需替换；自动附加 api-version。",
    },
    {
        "id": "openrouter", "label": "OpenRouter（聚合）", "kind": "openai",
        "default_base_url": "https://openrouter.ai/api/v1", "auth": "bearer",
        "api_key_hint": "sk-or-...（OpenRouter Key）",
        "capabilities": {"streaming": True, "tools": True, "vision": True, "structuredOutput": False},
        "models": [
            {"id": "anthropic/claude-3.5-sonnet", "label": "Claude 3.5 Sonnet（经 OpenRouter）"},
            {"id": "openai/gpt-4o", "label": "GPT-4o（经 OpenRouter）"},
            {"id": "deepseek/deepseek-r1", "label": "DeepSeek-R1（经 OpenRouter）", "note": "推理"},
        ],
    },
    {
        "id": "ollama", "label": "Ollama（本地）", "kind": "openai",
        "default_base_url": "http://localhost:11434/v1", "auth": "bearer",
        "api_key_hint": "（本地一般留空）",
        "capabilities": {"streaming": True, "tools": False, "vision": True, "structuredOutput": False},
        "models": [
            {"id": "llama3.1", "label": "Llama 3.1（本地拉取）"},
            {"id": "qwen2.5", "label": "Qwen2.5（本地拉取）"},
            {"id": "deepseek-r1", "label": "DeepSeek-R1（本地拉取）", "note": "推理"},
        ],
    },
]

PRESETS_BY_ID = {p["id"]: p for p in PRESETS}


def sse(wfile, obj):
    """向客户端发送一条 SSE 事件。"""
    wfile.write(("data: " + json.dumps(obj, ensure_ascii=False) + "\n\n").encode("utf-8"))
    wfile.flush()


# ----------------------------------------------------------------------------
# 动态拉取模型清单（移植自 zaomeng-model-fetcher.kt）
# ----------------------------------------------------------------------------
def fetch_models(preset, base_url, api_key):
    base = (base_url or preset["default_base_url"] or "").strip()
    if not base:
        return preset["models"], False
    base = base.rstrip("/")
    kind = preset["kind"]
    try:
        if kind == "openai" or kind == "azure":
            url = base + "/models"
            headers = {"Authorization": "Bearer " + (api_key or "")}
            if kind == "azure":
                url = base + "/models?api-version=2024-02-15-preview"
                headers = {"api-key": api_key or ""}
            data = _http_get_json(url, headers)
            arr = (data.get("data") if isinstance(data, dict) else None)
            if not arr:
                return preset["models"], False
            models = [{"id": m.get("id"), "label": m.get("id")} for m in arr if m.get("id")]
            models = [m for m in models if m["id"]]
            models.sort(key=lambda x: x["id"])
            return (models if models else preset["models"]), True
        elif kind == "anthropic":
            url = base + "/models"
            headers = {"x-api-key": api_key or "", "anthropic-version": "2023-06-01"}
            data = _http_get_json(url, headers)
            arr = data.get("data") if isinstance(data, dict) else None
            if not arr:
                return preset["models"], False
            models = []
            for m in arr:
                mid = m.get("id")
                if not mid:
                    continue
                models.append({"id": mid, "label": m.get("display_name") or mid})
            models.sort(key=lambda x: x["id"])
            return (models if models else preset["models"]), True
        elif kind == "gemini":
            url = base + "/models?key=" + (api_key or "")
            data = _http_get_json(url, {})
            arr = data.get("models") if isinstance(data, dict) else None
            if not arr:
                return preset["models"], False
            models = []
            for m in arr:
                full = m.get("name") or ""
                mid = full.split("/")[-1]
                if not mid:
                    continue
                models.append({"id": mid, "label": m.get("displayName") or mid})
            models.sort(key=lambda x: x["id"])
            return (models if models else preset["models"]), True
        else:
            return preset["models"], False
    except Exception:
        return preset["models"], False


def _http_get_json(url, headers):
    req = urllib.request.Request(url, headers=dict(headers, **{"Accept": "application/json"}))
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode("utf-8"))


# ----------------------------------------------------------------------------
# 构造多角色对话提示（NDJSON 协议，对齐 zaomeng DialoguePromptBuilder 思路）
# ----------------------------------------------------------------------------
PROTOCOL = (
    "你是一个小说角色对话引擎。根据下方「角色卡」「关系」「场景」，以自然、符合角色性格与关系的方式推进剧情对话。\n\n"
    "【输出协议，必须严格遵守】\n"
    "只输出 NDJSON：每行一个 JSON 对象，不要代码围栏、不要任何解释文字：\n"
    '{"speaker":"角色名","message":"这句台词","inner_thought":"（可选）内心独白"}\n'
    "- speaker 必须是角色卡中的某个名字，或「旁白」或「场景提示」。\n"
    "- 一轮可由多个角色依次发言（输出多行 NDJSON）。\n"
    "- 保持角色语气、立场、关系一致；不要输出角色名以外的说明。\n"
)


def build_prompt(characters, scene, history, user_input):
    pack_lines = []
    for c in characters:
        name = c.get("name", "").strip()
        if not name:
            continue
        persona = c.get("persona", "").strip()
        relation = c.get("relation", "").strip()
        line = f"- {name}：{persona}" if persona else f"- {name}"
        if relation:
            line += f"（关系：{relation}）"
        pack_lines.append(line)
    pack = "【角色卡】\n" + ("\n".join(pack_lines) if pack_lines else "（无）")
    scene_text = "【场景】\n" + (scene.strip() if scene.strip() else "（自由发挥）")
    hist_lines = []
    for h in history:
        sp = h.get("speaker", "").strip()
        msg = h.get("message", "").strip()
        if sp and msg:
            hist_lines.append(f"{sp}：{msg}")
    hist_text = "【已有对话】\n" + ("\n".join(hist_lines) if hist_lines else "（尚无）")
    instr = "【用户指令】\n" + (user_input.strip() if user_input.strip() else "请继续推进对话。")
    user_text = scene_text + "\n\n" + hist_text + "\n\n" + instr + "\n\n请输出新一轮 NDJSON 对话。"
    return pack, user_text


# ----------------------------------------------------------------------------
# 调用 Provider 并流式转发
# ----------------------------------------------------------------------------
def handle_chat(wfile, payload):
    pid = payload.get("provider_id", "mock")
    preset = PRESETS_BY_ID.get(pid)
    if not preset:
        sse(wfile, {"error": "unknown provider"})
        return
    kind = preset["kind"]
    characters = payload.get("characters", [])
    scene = payload.get("scene", "")
    history = payload.get("history", [])
    user_input = payload.get("user_input", "")
    model = payload.get("model", "")
    api_key = payload.get("api_key", "")
    base = (payload.get("base_url") or preset["default_base_url"] or "").strip()

    pack, user_text = build_prompt(characters, scene, history, user_input)
    system_text = PROTOCOL + "\n" + pack

    if kind == "mock":
        _stream_mock(wfile, characters)
        return

    if kind in ("openai", "azure"):
        url = base.rstrip("/") + "/chat/completions"
        headers = {"Content-Type": "application/json"}
        if kind == "azure":
            url += "?api-version=2024-02-15-preview"
            headers["api-key"] = api_key or ""
        else:
            headers["Authorization"] = "Bearer " + (api_key or "")
        body = {
            "model": model, "stream": True, "temperature": 0.9,
            "messages": [
                {"role": "system", "content": system_text},
                {"role": "user", "content": user_text},
            ],
        }
        _stream_openai(wfile, url, headers, body)
    elif kind == "anthropic":
        url = base.rstrip("/") + "/messages"
        headers = {"Content-Type": "application/json", "x-api-key": api_key or "",
                   "anthropic-version": "2023-06-01"}
        body = {
            "model": model, "max_tokens": 2048, "system": system_text,
            "messages": [{"role": "user", "content": user_text}],
        }
        _call_nonstream(wfile, url, headers, body)
    elif kind == "gemini":
        url = base.rstrip("/") + "/models/" + (model or "gemini-1.5-flash") + ":generateContent?key=" + (api_key or "")
        headers = {"Content-Type": "application/json"}
        body = {"contents": [{"role": "user", "parts": [{"text": system_text + "\n\n" + user_text}]}]}
        _call_gemini(wfile, url, headers, body)
    else:
        sse(wfile, {"error": "unsupported provider kind: " + kind})


def _stream_openai(wfile, url, headers, body):
    req = urllib.request.Request(url, data=json.dumps(body, ensure_ascii=False).encode("utf-8"), headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            for raw_line in resp:
                line = raw_line.decode("utf-8", "ignore").strip()
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if data == "[DONE]":
                    break
                try:
                    obj = json.loads(data)
                except Exception:
                    continue
                try:
                    delta = obj["choices"][0]["delta"]
                except Exception:
                    continue
                text = delta.get("content") or delta.get("reasoning") or ""
                if text:
                    sse(wfile, {"text": text})
    except Exception as e:
        sse(wfile, {"error": "provider request failed: " + str(e)})


def _call_nonstream(wfile, url, headers, body):
    req = urllib.request.Request(url, data=json.dumps(body, ensure_ascii=False).encode("utf-8"), headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            obj = json.loads(resp.read().decode("utf-8"))
            if "content" in obj and isinstance(obj["content"], list):  # anthropic
                text = "".join(b.get("text", "") for b in obj["content"] if b.get("type") == "text")
            else:
                text = obj.get("choices", [{}])[0].get("message", {}).get("content", "")
            sse(wfile, {"text": text})
    except Exception as e:
        sse(wfile, {"error": "provider request failed: " + str(e)})


def _call_gemini(wfile, url, headers, body):
    req = urllib.request.Request(url, data=json.dumps(body, ensure_ascii=False).encode("utf-8"), headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            obj = json.loads(resp.read().decode("utf-8"))
            parts = obj.get("candidates", [{}])[0].get("content", {}).get("parts", [])
            text = "".join(p.get("text", "") for p in parts)
            sse(wfile, {"text": text})
    except Exception as e:
        sse(wfile, {"error": "provider request failed: " + str(e)})


def _stream_mock(wfile, characters):
    names = [c.get("name", "角色") for c in characters if c.get("name")]
    if not names:
        names = ["李白", "杜甫"]
    script = [
        {"speaker": names[0], "message": "（负手而立）今夜月色甚好，不知阁下可有诗兴？"},
        {"speaker": names[1] if len(names) > 1 else "旁白", "message": "月色虽好，却照尽人间离愁。我倒想起远方的故人。"},
        {"speaker": "旁白", "message": "一阵晚风拂过，烛火轻摇。"},
        {"speaker": names[0], "message": "故人嘛……举杯邀明月，对影成三人，便不算孤身了。"},
    ]
    import time
    for item in script:
        # 模拟模型逐字流式输出 NDJSON
        line = json.dumps(item, ensure_ascii=False)
        for ch in line:
            sse(wfile, {"text": ch})
            time.sleep(0.012)
        sse(wfile, {"text": "\n"})
        time.sleep(0.15)


# ----------------------------------------------------------------------------
# HTTP 处理
# ----------------------------------------------------------------------------
class Handler(BaseHTTPRequestHandler):
    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.end_headers()

    def do_GET(self):
        if self.path == "/" or self.path == "/index.html":
            self._serve_file("index.html", "text/html; charset=utf-8")
        elif self.path == "/api/providers":
            self._json(PRESETS)
        elif self.path.startswith("/static/"):
            self._serve_static(self.path[len("/static/"):])
        else:
            self.send_error(404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0) or 0)
        raw = self.rfile.read(length) if length else b"{}"
        try:
            payload = json.loads(raw.decode("utf-8") or "{}")
        except Exception:
            payload = {}
        if self.path == "/api/models":
            pid = payload.get("provider_id", "")
            preset = PRESETS_BY_ID.get(pid)
            if not preset:
                self._json({"models": [], "live": False})
                return
            models, live = fetch_models(preset, payload.get("base_url", ""), payload.get("api_key", ""))
            self._json({"models": models, "live": live})
        elif self.path == "/api/chat":
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self._cors()
            self.end_headers()
            try:
                handle_chat(self.wfile, payload)
            except Exception as e:
                try:
                    sse(self.wfile, {"error": str(e)})
                except Exception:
                    pass
        else:
            self.send_error(404)

    def _json(self, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self._cors()
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _serve_file(self, name, ctype):
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), name)
        if not os.path.exists(path):
            self.send_error(404)
            return
        with open(path, "rb") as f:
            body = f.read()
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _serve_static(self, name):
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static", name)
        if not os.path.exists(path) or ".." in name:
            self.send_error(404)
            return
        ctype = "text/css; charset=utf-8" if name.endswith(".css") else "application/javascript; charset=utf-8"
        with open(path, "rb") as f:
            body = f.read()
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass  # 安静


import os


def main():
    port = 8000
    if len(sys.argv) > 1:
        try:
            port = int(sys.argv[1])
        except Exception:
            pass
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"造梦轻量版已启动： http://127.0.0.1:{port}  （Ctrl+C 退出）")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止。")


if __name__ == "__main__":
    main()
