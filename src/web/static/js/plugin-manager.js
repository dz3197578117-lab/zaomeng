(() => {
  const byId = (id) => document.getElementById(id);

  function setStatus(message, isError = false) {
    const node = byId("plugin-manager-status");
    if (!node) return;
    node.textContent = String(message || "");
    node.classList.toggle("is-error", Boolean(isError));
  }

  function permissionLabel(permission) {
    return {
      "chat.context.read": "读取聊天上下文",
      "chat.cast.write": "向当前会话加入临时角色",
      "chat.draft.write": "写入聊天草稿",
      "generation.enhance": "增强回复生成",
      "model.invoke": "调用模型",
      "storage.read": "读取插件存储",
      "storage.write": "写入插件存储",
      "network.access": "访问网络",
    }[permission] || permission;
  }

  function makeText(tag, text, className = "") {
    const node = document.createElement(tag);
    node.textContent = String(text || "");
    if (className) node.className = className;
    return node;
  }

  async function request(path, options = {}) {
    if (typeof apiJson !== "function") throw new Error("插件 API 尚未就绪。");
    return apiJson(path, options, "插件操作失败。");
  }

  function renderPlugins(items) {
    const root = byId("plugin-manager-list");
    if (!root) return;
    root.replaceChildren();
    if (!Array.isArray(items) || items.length === 0) {
      root.appendChild(makeText("p", "当前没有发现插件。把插件放入运行目录后点击刷新。", "panel-copy"));
      return;
    }
    items.forEach((plugin) => {
      const card = document.createElement("article");
      card.className = "plugin-manager-card";
      const head = document.createElement("div");
      head.className = "plugin-manager-card-head";
      const title = document.createElement("div");
      title.appendChild(makeText("strong", plugin.name || plugin.id));
      title.appendChild(makeText(
        "span",
        `${plugin.source === "official" ? "官方" : "第三方"} · v${plugin.version || "-"} · API ${plugin.apiVersion || "-"}`,
      ));
      const toggleButton = document.createElement("button");
      toggleButton.type = "button";
      toggleButton.className = plugin.enabled ? "soft-button" : "primary-button";
      toggleButton.textContent = plugin.enabled ? "停用" : "启用";
      toggleButton.addEventListener("click", async () => {
        toggleButton.disabled = true;
        setStatus(`正在${plugin.enabled ? "停用" : "启用"}「${plugin.name || plugin.id}」...`);
        try {
          await request(`/api/web/plugins/${encodeURIComponent(plugin.id)}/${plugin.enabled ? "disable" : "enable"}`, { method: "POST" });
          await loadPlugins(false);
        } catch (error) {
          setStatus(error?.message || "插件状态更新失败。", true);
          toggleButton.disabled = false;
        }
      });
      head.append(title, toggleButton);
      card.appendChild(head);
      if (plugin.description) card.appendChild(makeText("p", plugin.description, "plugin-manager-description"));
      const actions = plugin.contributes?.chatActions || [];
      if (actions.length) {
        card.appendChild(makeText("p", `聊天动作：${actions.map((item) => item.title).filter(Boolean).join("、")}`, "plugin-manager-meta"));
      }
      const enhancers = plugin.contributes?.generationEnhancers || [];
      if (enhancers.length) {
        card.appendChild(makeText("p", `聊天生成增强：${enhancers.map((item) => item.title).filter(Boolean).join("、")}（在各聊天中开关）`, "plugin-manager-meta"));
      }
      if (Array.isArray(plugin.permissions) && plugin.permissions.length) {
        card.appendChild(makeText("p", `权限：${plugin.permissions.map(permissionLabel).join(" · ")}`, "plugin-manager-meta"));
      }
      if (plugin.error) card.appendChild(makeText("p", plugin.error, "plugin-manager-error"));
      root.appendChild(card);
    });
  }

  async function loadPlugins(refresh = false) {
    setStatus(refresh ? "正在重新发现插件..." : "正在读取插件...");
    try {
      const payload = await request("/api/web/plugins" + (refresh ? "/refresh" : ""), refresh ? { method: "POST" } : {});
      renderPlugins(payload?.items || []);
      setStatus(refresh ? "插件列表已刷新。" : "");
    } catch (error) {
      setStatus(error?.message || "插件列表读取失败。", true);
    }
  }

  function closePluginManagerModal() {
    byId("plugin-manager-modal")?.classList.add("hidden");
    if (typeof syncModalScrollLock === "function") syncModalScrollLock();
  }

  function openPluginManagerModal() {
    if (typeof closeSettingsModal === "function") closeSettingsModal();
    byId("plugin-manager-modal")?.classList.remove("hidden");
    if (typeof syncModalScrollLock === "function") syncModalScrollLock();
    loadPlugins(false);
  }

  byId("open-plugin-manager-button")?.addEventListener("click", openPluginManagerModal);
  byId("close-plugin-manager-button")?.addEventListener("click", closePluginManagerModal);
  byId("refresh-plugins-button")?.addEventListener("click", () => loadPlugins(true));
  document.querySelector("[data-plugin-manager-close='true']")?.addEventListener("click", closePluginManagerModal);
  window.openPluginManagerModal = openPluginManagerModal;
  window.closePluginManagerModal = closePluginManagerModal;
})();
