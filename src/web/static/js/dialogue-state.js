((root, factory) => {
  const api = factory();

  if (typeof module === "object" && module.exports) {
    module.exports = api;
  }

  root.__ZAOMENG_DIALOGUE_STATE_TOOLS__ = api;
})(typeof window !== "undefined" ? window : globalThis, () => {
  function trimInlineMessage(value) {
    const text = String(value || "").replace(/\s+/g, " ").trim();
    if (!text) return "";
    return text.length > 88 ? `${text.slice(0, 88)}...` : text;
  }

  function buildDialogueMemorySnapshot(
    session,
    { humanizeMode = (value) => String(value || ""), formatWeakTime = () => "" } = {}
  ) {
    const summary = session?.session_memory_summary || {};
    const summaryMode = String(summary.mode || "").trim();
    const summaryModeLabel = String(summary.mode_display || "").trim();
    const summaryRecap = String(summary.recap || "").trim();
    const summaryCast = String(summary.cast || "").trim();
    const summaryRelation = String(summary.relation_drift || "").trim();
    const summaryPerspective = String(summary.perspective || "").trim();
    const summaryScene = String(summary.scene_frame || "").trim();
    const summaryLocation = String(summary.current_location || "").trim();
    const summaryCompanions = String(summary.current_companions || "").trim();
    const summaryCommitments = String(summary.pending_commitments || "").trim();
    const summaryWorld = String(summary.world || "").trim();
    const summaryUpdated = String(summary.updated_at || "").trim();

    if (
      summaryRecap ||
      summaryCast ||
      summaryRelation ||
      summaryPerspective ||
      summaryScene ||
      summaryLocation ||
      summaryCompanions ||
      summaryCommitments ||
      summaryWorld
    ) {
      return {
        modeLabel: summaryModeLabel || humanizeMode(summaryMode || session?.mode || session?.session_card?.mode || "observe"),
        recap: summaryRecap || "这局刚开场，回顾会在这里滚动更新。",
        cast: summaryCast || "人物发言次序会在这里收住。",
        relation: summaryRelation || "关系线会在这里滚动提示。",
        perspective: summaryPerspective || "你当前的入场方式会在这里提示。",
        scene: summaryScene || "当前这幕的地点、气氛与推进方向会在这里提醒你。",
        location: summaryLocation || "当前落点会在这里提醒你。",
        companions: summaryCompanions || "现在与你同场的人会在这里提醒你。",
        commitments: summaryCommitments || "还没收口的承诺或待推进事项会在这里提醒你。",
        world: summaryWorld || "当前局势里的动作与情绪线会在这里提醒你。",
        updated: formatWeakTime(summaryUpdated) || formatWeakTime(session?.updated_at) || "刚刚更新",
      };
    }

    const mode = String(session?.mode || session?.session_card?.mode || "observe").trim() || "observe";
    const modeLabel = humanizeMode(mode) || mode;
    const transcript = Array.isArray(session?.transcript) ? session.transcript : [];
    const castRows = transcript.filter((item) => item?.role === "character");
    const worldRows = transcript.filter((item) => item?.role === "scene" || item?.role === "director");
    const lastRows = transcript.slice(-6);
    const lastCharacter = castRows.length ? castRows[castRows.length - 1] : null;
    const lastWorld = worldRows.length ? worldRows[worldRows.length - 1] : null;
    const speakerOrder = [];
    const seen = new Set();
    castRows.forEach((item) => {
      const speaker = String(item?.speaker || "").trim();
      if (!speaker || seen.has(speaker)) return;
      seen.add(speaker);
      speakerOrder.push(speaker);
    });
    const lastBeatMessages = lastRows
      .filter((item) => String(item?.message || "").trim())
      .map((item) => trimInlineMessage(item.message))
      .slice(-3);

    let recap = "这局刚开场，回顾会在这里滚动更新。";
    if (lastBeatMessages.length) {
      recap = `最近一拍：${lastBeatMessages.join(" / ")}`;
    }

    let cast = "人物发言次序会在这里收住。";
    if (speakerOrder.length) {
      cast = `当前主要在场：${speakerOrder.slice(0, 5).join("、")}${speakerOrder.length > 5 ? "..." : ""}`;
    } else if (lastCharacter?.speaker) {
      cast = `${lastCharacter.speaker} 刚刚接话：${trimInlineMessage(lastCharacter.message)}`;
    }

    let relation = "关系线会在这里滚动提示。";
    if (castRows.length >= 2) {
      const recent = castRows
        .slice(-4)
        .map((item) => String(item?.speaker || "").trim())
        .filter(Boolean);
      if (recent.length >= 2) {
        relation = `最近接话链：${recent.join(" → ")}`;
      }
    } else if (speakerOrder.length) {
      relation = `本局关键人物：${speakerOrder.slice(0, 4).join("、")}`;
    }

    let perspective = "你当前的入场方式会在这里提示。";
    if (mode === "act") {
      const controlled = String(session?.session_card?.controlled_character || "").trim() || "该角色";
      perspective = `你正以「${controlled}」发言，其他人会按角色关系回应。`;
    } else if (mode === "insert") {
      const selfName = String(session?.session_card?.self_insert?.display_name || "").trim() || "你";
      const identity = String(session?.session_card?.self_insert?.scene_identity || "").trim();
      perspective = identity ? `你以「${selfName}」入场（${identity}）。` : `你以「${selfName}」入场，直接参与这幕。`;
    } else {
      perspective = "你在旁观推进模式里，主要作用是推动局势进入下一拍。";
    }

    let world = "当前局势里的动作与情绪线会在这里提醒你。";
    let scene = "当前这幕的地点、气氛与推进方向会在这里提醒你。";
    let locationSummary = "";
    let companions = cast;
    let commitments = "";
    const sceneCard = session?.session_card?.scene_card || {};
    if (sceneCard && (sceneCard.title || sceneCard.location || sceneCard.atmosphere || sceneCard.scene_drive)) {
      const sceneBits = [sceneCard.title, sceneCard.location, sceneCard.atmosphere].filter(Boolean);
      const drive = trimInlineMessage(sceneCard.scene_drive || sceneCard.opening_situation || "");
      scene = sceneBits.length ? `挂载场景：${sceneBits.join(" / ")}${drive ? ` · ${drive}` : ""}` : drive || scene;
    }
    const overview = session?.runtime_state_overview || {};
    const overviewLocation = trimInlineMessage(String(overview.current_location || "").trim());
    const overviewCompanions = trimInlineMessage(String(overview.current_companions || "").trim());
    const overviewCommitments = trimInlineMessage(String(overview.pending_commitments || "").trim());
    if (overviewLocation) {
      locationSummary = overviewLocation;
    } else if (sceneCard?.location) {
      locationSummary = trimInlineMessage(String(sceneCard.location || "").trim());
    }
    if (overviewCompanions) {
      companions = overviewCompanions;
    }
    if (overviewCommitments) {
      commitments = overviewCommitments;
    }
    if (lastWorld?.message) {
      world = trimInlineMessage(lastWorld.message);
    } else if (lastCharacter?.message) {
      world = `人物最新情绪线：${trimInlineMessage(lastCharacter.message)}`;
    }

    return {
      modeLabel,
      recap,
      cast,
      relation,
      perspective,
      scene,
      location: locationSummary || "当前落点会在这里提醒你。",
      companions: companions || "现在与你同场的人会在这里提醒你。",
      commitments: commitments || "还没收口的承诺或待推进事项会在这里提醒你。",
      world,
      updated: formatWeakTime(session?.updated_at) || "刚刚更新",
    };
  }

  function buildDialogueStateSnapshot(session) {
    const overview = session?.runtime_state_overview || null;
    if (overview && typeof overview === "object") {
      return {
        present: Array.isArray(overview.present) ? overview.present.filter(Boolean) : [],
        offstage: Array.isArray(overview.offstage) ? overview.offstage.filter(Boolean) : [],
        pills: Array.isArray(overview.pills) ? overview.pills.filter((item) => String(item?.text || "").trim()) : [],
        tension: trimInlineMessage(String(overview.tension || "").trim()) || "这一拍的情绪和冲突会收在这里。",
        characterRows: Array.isArray(overview.character_rows) ? overview.character_rows : [],
        relationRows: Array.isArray(overview.relation_rows) ? overview.relation_rows : [],
        eventRows: Array.isArray(overview.event_rows) ? overview.event_rows : [],
        statusLine: trimInlineMessage(String(overview.status_line || "").trim()),
        nextHint: trimInlineMessage(String(overview.next_hint || "").trim()),
      };
    }
    const state = session?.state || {};
    const scene = state?.scene || {};
    const presence = state?.presence || {};
    const progression = state?.progression || {};
    const progress = session?.scene_progress || {};
    const present = Array.isArray(progress?.present_participants) ? progress.present_participants : (presence?.present_participants || []);
    const offstage = Array.isArray(progress?.offstage_participants) ? progress.offstage_participants : (presence?.offstage_participants || []);
    const location = String(progress?.location || scene?.location || "").trim();
    const timeHint = String(progress?.time_hint || scene?.time_hint || "").trim();
    const atmosphere = trimInlineMessage(String(progress?.atmosphere_summary || scene?.atmosphere_summary || "").trim());
    const beatMaturity = Number(progress?.beat_maturity || progression?.beat_maturity || 0) || 0;
    const canShift = Boolean(progress?.should_offer_scene_shift ?? progression?.should_offer_scene_shift);
    const shiftReason = trimInlineMessage(String(progress?.scene_shift_reason || progression?.scene_shift_reason || "").trim());
    const tension = trimInlineMessage(
      String(progress?.world_tension_summary || progression?.world_tension_summary || session?.session_memory_summary?.world || "").trim()
    ) || "这一拍的情绪和冲突会收在这里。";
    const characterSnapshots = session?.character_snapshots || state?.characters?.snapshots || {};
    const relationDelta = session?.relation_delta || state?.relations?.delta || {};

    const pills = [];
    if (location) pills.push({ text: `地点 · ${location}` });
    if (timeHint) pills.push({ text: `时间 · ${timeHint}` });
    if (atmosphere) pills.push({ text: `氛围 · ${atmosphere}` });
    if (beatMaturity > 0) pills.push({ text: `推进 ${Math.max(0, Math.min(100, Math.round(beatMaturity)))}/100` });
    if (canShift) pills.push({ text: shiftReason ? `可转场 · ${shiftReason}` : "这一拍可以顺势转场" });

    const characterRows = Object.entries(characterSnapshots)
      .map(([name, snapshot]) => {
        const item = snapshot || {};
        const parts = [];
        const presentState = String(item?.present_state || "").trim();
        if (presentState === "onstage") parts.push("在场");
        if (presentState === "offstage") parts.push("离场");
        if (item?.mood) parts.push(String(item.mood).trim());
        if (item?.interaction_state) parts.push(String(item.interaction_state).trim());
        if (item?.focus) parts.push(`看向 ${String(item.focus).trim()}`);
        if (item?.scene_location && String(item.scene_location).trim() !== location) {
          parts.push(String(item.scene_location).trim());
        }
        return {
          title: String(name || "").trim(),
          copy: parts.filter(Boolean).join(" · "),
          weight: presentState === "onstage" ? 0 : 1,
        };
      })
      .filter((item) => item.title)
      .sort((left, right) => {
        if (left.weight !== right.weight) return left.weight - right.weight;
        return left.title.localeCompare(right.title, "zh-Hans-CN");
      })
      .slice(0, 4)
      .map(({ title, copy }) => ({ title, copy: copy || "这一拍还没有额外漂移。" }));

    const relationRows = Object.entries(relationDelta)
      .map(([pairKey, delta]) => {
        const item = delta || {};
        const metrics = [];
        [["trust", "信任"], ["affection", "好感"], ["hostility", "敌意"], ["ambiguity", "摇摆"]].forEach(([field, label]) => {
          const value = Number(item?.[field] || 0) || 0;
          if (!value) return;
          metrics.push(`${label}${value > 0 ? "+" : ""}${value}`);
        });
        const lastEvent = trimInlineMessage(String(item?.last_event || "").trim());
        return {
          title: String(pairKey || "").trim().replace(/_/g, " · "),
          copy: metrics.length ? `${metrics.join(" / ")}${lastEvent ? ` · ${lastEvent}` : ""}` : (lastEvent || "这组关系本局有变化。"),
        };
      })
      .filter((item) => item.title)
      .slice(0, 3);

    const eventKindLabel = {
      scene_transition: "转场",
      cast_enter: "入场",
      cast_exit: "离场",
      atmosphere_shift: "气氛变化",
      time_change: "时间推进",
      environment_change: "环境变化",
      beat_complete: "一拍收束",
      relationship_shift: "关系变化",
      micro_action: "细微动作",
    };
    return {
      present: Array.isArray(present) ? present.filter(Boolean) : [],
      offstage: Array.isArray(offstage) ? offstage.filter(Boolean) : [],
      pills,
      tension,
      characterRows,
      relationRows,
      eventRows: Array.isArray(session?.event_signals?.recent)
        ? session.event_signals.recent.slice(-4).map((item) => ({
            title: [
              eventKindLabel[String(item?.kind || "").trim()] || String(item?.kind || "").trim(),
              String(item?.actor || "").trim(),
              String(item?.target || "").trim(),
            ].filter(Boolean).join(" · ") || "事件",
            copy: trimInlineMessage(String(item?.cue || "").trim()) || "这一拍有了新波动。",
          }))
        : [],
      statusLine: "",
      nextHint: "",
    };
  }

  return { trimInlineMessage, buildDialogueMemorySnapshot, buildDialogueStateSnapshot };
});
