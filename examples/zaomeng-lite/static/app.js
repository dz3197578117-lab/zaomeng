"use strict";

// ====================== 状态与持久化 ======================
const LS_KEY = "zaomeng_lite_cfg_v1";
const state = {
  provider_id: "mock",
  api_key: "",
  base_url: "",
  model: "",
  characters: [{ name: "李白", persona: "盛唐浪漫主义诗人，豪放不羁，嗜酒。" }, { name: "杜甫", persona: "现实主义诗人，忧国忧民，沉郁顿挫。", relation: "与李白亦敌亦友" }],
  scene: "长安城外，月夜，酒肆。",
  history: [],
};

function save() {
  const cfg = {
    provider_id: state.provider_id, api_key: state.api_key, base_url: state.base_url,
    model: state.model, characters: state.characters, scene: state.scene, history: state.history,
  };
  try { localStorage.setItem(LS_KEY, JSON.stringify(cfg)); } catch (e) {}
}
function load() {
  try {
    const cfg = JSON.parse(localStorage.getItem(LS_KEY) || "null");
    if (cfg) Object.assign(state, cfg);
  } catch (e) {}
}

// ====================== DOM ======================
const $ = (id) => document.getElementById(id);
const providersEl = $("provider"), apikeyEl = $("apikey"), baseurlEl = $("baseurl");
const modelEl = $("model"), noteEl = $("providerNote"), charListEl = $("charList");
const sceneEl = $("scene"), chatEl = $("chat"), userInputEl = $("userInput");

let PRESETS = [];

// ====================== Provider / 模型 ======================
async function loadProviders() {
  const r = await fetch("/api/providers");
  PRESETS = await r.json();
  providersEl.innerHTML = "";
  for (const p of PRESETS) {
    const o = document.createElement("option");
    o.value = p.id; o.textContent = p.label;
    providersEl.appendChild(o);
  }
  providersEl.value = state.provider_id || PRESETS[0].id;
  onProviderChange(false);
}

function currentPreset() {
  return PRESETS.find((p) => p.id === providersEl.value) || PRESETS[0];
}

function onProviderChange(saveFlag) {
  const p = currentPreset();
  state.provider_id = p.id;
  noteEl.textContent = p.notes || "";
  apikeyEl.placeholder = p.api_key_hint || "API Key";
  if (!state.base_url) baseurlEl.value = p.default_base_url || "";
  else baseurlEl.value = state.base_url;
  // 模型下拉先填预设（拉取成功会覆盖）
  modelEl.innerHTML = "";
  for (const m of p.models) {
    const o = document.createElement("option");
    o.value = m.id; o.textContent = m.label + (m.note ? "（" + m.note + "）" : "");
    modelEl.appendChild(o);
  }
  if (state.model) modelEl.value = state.model;
  if (saveFlag) save();
}

async function fetchModels() {
  const p = currentPreset();
  const body = { provider_id: p.id, base_url: baseurlEl.value.trim(), api_key: apikeyEl.value.trim() };
  modelEl.innerHTML = "<option>拉取中…</option>";
  try {
    const r = await fetch("/api/models", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
    const data = await r.json();
    modelEl.innerHTML = "";
    for (const m of data.models) {
      const o = document.createElement("option");
      o.value = m.id; o.textContent = m.label + (m.note ? "（" + m.note + "）" : "");
      modelEl.appendChild(o);
    }
    noteEl.textContent = (data.live ? "✅ 已从接口实时拉取 " : "⚠️ 接口拉取失败，已回退预设：") + data.models.length + " 个模型。\n" + (p.notes || "");
  } catch (e) {
    noteEl.textContent = "拉取出错：" + e.message;
  }
}

// ====================== 角色卡 ======================
function renderChars() {
  charListEl.innerHTML = "";
  state.characters.forEach((c, i) => {
    const div = document.createElement("div");
    div.className = "char-item";
    div.innerHTML = `
      <div class="top">
        <input class="nm" placeholder="角色名" value="${esc(c.name)}" />
        <input class="rl" placeholder="关系（可选）" value="${esc(c.relation || "")}" />
        <button class="del">×</button>
      </div>
      <textarea placeholder="人设 / 性格 / 背景" rows="2">${esc(c.persona || "")}</textarea>`;
    const [nm, rl, del, ta] = [div.querySelector(".nm"), div.querySelector(".rl"), div.querySelector(".del"), div.querySelector("textarea")];
    nm.oninput = () => { state.characters[i].name = nm.value; save(); };
    rl.oninput = () => { state.characters[i].relation = rl.value; save(); };
    ta.oninput = () => { state.characters[i].persona = ta.value; save(); };
    del.onclick = () => { state.characters.splice(i, 1); renderChars(); save(); };
    charListEl.appendChild(div);
  });
}

// ====================== NDJSON 增量投影器（端口自造梦 DialogueStreamParser） ======================
function createProjector() {
  let buffer = "";
  const emitted = {};
  const SPK = '"speaker"'; const MSG = '"message"'; const INN = '"inner_thought"';

  function decodePartialString(text, start) {
    let i = start, chars = "", c;
    while (i < text.length) {
      c = text[i];
      if (c === '"') return { value: chars, complete: true, end: i + 1 };
      if (c !== "\\") { chars += c; i++; continue; }
      if (i + 1 >= text.length) break;
      const e = text[i + 1];
      const map = { '"': '"', "\\": "\\", "/": "/", b: "\b", f: "\f", n: "\n", r: "\r", t: "\t" };
      if (e === "u") {
        if (i + 6 > text.length) break;
        const code = parseInt(text.substr(i + 2, 4), 16);
        chars += isNaN(code) ? "�" : String.fromCharCode(code);
        i += 6;
      } else { chars += (map[e] !== undefined ? map[e] : e); i += 2; }
    }
    return { value: chars, complete: false, end: i };
  }

  function findObjectBounds(text, pos) {
    const stack = []; let targetStart = -1, inStr = false, esc = false;
    for (let i = 0; i < text.length; i++) {
      if (i === pos) targetStart = stack.length ? stack[stack.length - 1] : -1;
      if (inStr) {
        if (esc) esc = false;
        else if (text[i] === "\\") esc = true;
        else if (text[i] === '"') inStr = false;
        continue;
      }
      if (text[i] === '"') inStr = true;
      else if (text[i] === "{") stack.push(i);
      else if (text[i] === "}") {
        if (stack.length) { const s = stack.pop(); if (s === targetStart) return [s, i + 1]; }
      }
    }
    return targetStart >= 0 ? [targetStart, text.length] : [0, text.length];
  }

  function project() {
    const events = [];
    const spkIdxs = [];
    let re = new RegExp(SPK + '\\s*:\\s*"', "g"), m;
    while ((m = re.exec(buffer)) !== null) spkIdxs.push(m.index);
    let idx = 0;
    for (const mi of spkIdxs) {
      const tail = buffer.substr(mi + SPK.length);
      const cm = tail.match(/^\s*:\s*/);
      if (!cm) continue;
      const after = mi + SPK.length + cm[0].length;
      const sp = decodePartialString(buffer, after);
      if (!sp.complete || !sp.value.trim()) continue;
      const [os, oe] = findObjectBounds(buffer, mi);
      const msgM = new RegExp(MSG + '\\s*:\\s*"').exec(buffer.substr(os));
      if (!msgM || os + msgM.index >= oe) continue;
      const msg = decodePartialString(buffer, os + msgM.index + msgM[0].length);
      if (!msg.value) continue;
      const innM = new RegExp(INN + '\\s*:\\s*"').exec(buffer.substr(os));
      let inn = "";
      if (innM && os + innM.index < oe) inn = decodePartialString(buffer, os + innM.index + innM[0].length).value;
      const speaker = sp.value.trim();
      const role = (speaker === "旁白" || speaker === "场景提示") ? "scene" : "assistant";
      emit(events, idx, speaker, role, "message", msg.value);
      if (inn) emit(events, idx, speaker, role, "inner_thought", inn);
      idx++;
    }
    return events;
  }

  function emit(events, index, speaker, role, field, value) {
    const key = index + ":" + field;
    const prev = emitted[key] || 0;
    if (value.length <= prev) return;
    const suffix = value.substring(prev);
    emitted[key] = value.length;
    events.push({ index, speaker, role, field, text: suffix });
  }

  return {
    feed(delta) { buffer += delta; return project(); },
  };
}

// ====================== 对话渲染 ======================
const bubbles = {}; // key: index:speaker -> {el, msgEl, thinkEl}

function newBubble(speaker, role) {
  const el = document.createElement("div");
  el.className = "msg " + role;
  const spk = document.createElement("div"); spk.className = "spk"; spk.textContent = speaker;
  const msg = document.createElement("div"); msg.className = "body";
  if (role === "scene") { el.textContent = ""; el.appendChild(msg); }
  else { el.appendChild(spk); el.appendChild(msg); }
  chatEl.appendChild(el);
  chatEl.scrollTop = chatEl.scrollHeight;
  return { el, msg };
}

function renderEvents(events) {
  for (const ev of events) {
    const key = ev.index + ":" + ev.speaker;
    if (!bubbles[key]) bubbles[key] = newBubble(ev.speaker, ev.role);
    const b = bubbles[key];
    if (ev.field === "message") b.msg.textContent += ev.text;
    else if (ev.field === "inner_thought") {
      let t = b.el.querySelector(".think");
      if (!t) { t = document.createElement("div"); t.className = "think"; b.el.appendChild(t); }
      t.textContent += ev.text;
    }
    chatEl.scrollTop = chatEl.scrollHeight;
  }
}

function addUserMsg(text) {
  const el = document.createElement("div");
  el.className = "msg user";
  el.textContent = text;
  chatEl.appendChild(el);
  chatEl.scrollTop = chatEl.scrollHeight;
}

// ====================== 发送（流式） ======================
let sending = false;
async function send() {
  if (sending) return;
  const text = userInputEl.value.trim();
  if (!text) return;
  sending = true; $("send").disabled = true;
  addUserMsg(text);
  state.history.push({ speaker: "用户", message: text });
  userInputEl.value = "";

  // 重置本轮回显
  for (const k in bubbles) delete bubbles[k];
  const proj = createProjector();
  const cursor = document.createElement("span"); cursor.className = "cursor";

  const payload = {
    provider_id: providersEl.value,
    base_url: baseurlEl.value.trim(),
    api_key: apikeyEl.value.trim(),
    model: modelEl.value,
    characters: state.characters.filter((c) => c.name.trim()),
    scene: sceneEl.value,
    history: state.history.slice(0, -1),
    user_input: text,
  };

  try {
    const resp = await fetch("/api/chat", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) });
    const reader = resp.body.getReader();
    const dec = new TextDecoder();
    let buf = "";
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      let i;
      while ((i = buf.indexOf("\n\n")) >= 0) {
        const evt = buf.slice(0, i); buf = buf.slice(i + 2);
        const dataLines = evt.split("\n").filter((l) => l.startsWith("data:"));
        for (const dl of dataLines) {
          const j = dl.slice(5).trim();
          let obj; try { obj = JSON.parse(j); } catch (e) { continue; }
          if (obj.error) { addSystemNote("⚠️ " + obj.error); continue; }
          if (obj.text) {
            const events = proj.feed(obj.text);
            renderEvents(events);
          }
        }
      }
    }
  } catch (e) {
    addSystemNote("⚠️ 请求失败：" + e.message);
  }

  // 汇总本轮回写 history（从 bubbles 取完整文本）
  flushHistory();
  sending = false; $("send").disabled = false;
}

function flushHistory() {
  // 本回合的台词已渲染在 bubbles 中，回读整段文本写入 history
  const seen = {};
  for (const k in bubbles) {
    const spk = k.split(":").slice(1).join(":");
    if (spk === "用户") continue;
    const b = bubbles[k];
    if (!seen[spk]) seen[spk] = b.msg.textContent;
  }
  for (const spk in seen) {
    state.history.push({ speaker: spk, message: seen[spk] });
  }
  // 裁剪 history 长度，避免无限增长（对齐造梦「只读取有界近期 transcript」原则）
  if (state.history.length > 40) state.history = state.history.slice(-40);
  save();
}

function addSystemNote(text) {
  const el = document.createElement("div");
  el.className = "msg scene"; el.textContent = text;
  chatEl.appendChild(el);
  chatEl.scrollTop = chatEl.scrollHeight;
}

function esc(s) { return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;"); }

// ====================== 事件绑定 / 初始化 ======================
providersEl.onchange = () => onProviderChange(true);
apikeyEl.oninput = () => { state.api_key = apikeyEl.value; save(); };
baseurlEl.oninput = () => { state.base_url = baseurlEl.value; save(); };
modelEl.onchange = () => { state.model = modelEl.value; save(); };
sceneEl.oninput = () => { state.scene = sceneEl.value; save(); };
$("fetchModels").onclick = fetchModels;
$("addChar").onclick = () => { state.characters.push({ name: "", persona: "", relation: "" }); renderChars(); save(); };
$("send").onclick = send;
userInputEl.addEventListener("keydown", (e) => { if ((e.ctrlKey || e.metaKey) && e.key === "Enter") send(); });
$("resetCfg").onclick = () => { localStorage.removeItem(LS_KEY); location.reload(); };

(async function init() {
  load();
  await loadProviders();
  baseurlEl.value = state.base_url || currentPreset().default_base_url || "";
  apikeyEl.value = state.api_key || "";
  sceneEl.value = state.scene || "";
  modelEl.value = state.model || modelEl.value;
  renderChars();
})();
