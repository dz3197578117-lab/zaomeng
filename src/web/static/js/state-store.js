((root, factory) => {
  const api = factory();

  if (typeof module === "object" && module.exports) {
    module.exports = api;
  }

  root.__ZAOMENG_STATE_STORE_TOOLS__ = api;
  if (!root.__ZAOMENG_STATE_STORE__) {
    const makeReactive = root.Vue?.shallowReactive || root.Vue?.reactive;
    root.__ZAOMENG_STATE_STORE__ = api.createStateStore({ makeReactive });
  }
})(typeof window !== "undefined" ? window : globalThis, () => {
  function createInitialState() {
    return {
      currentRunId: "",
      currentRun: null,
      currentDialogueSessionId: "",
      currentDialogueSession: null,
      modelSettings: {
        configured: false,
        provider: "",
        model: "",
        base_url: "",
        max_tokens: 0,
        api_key_configured: false,
      },
      runCreationPending: false,
      runPollTimer: null,
      chatSetupPrefilledForRunId: "",
      sidebarCollapsed: false,
      sessionBooting: false,
      workflowBootPending: true,
      recentSessionsRequestId: 0,
      recentSessionsCache: [],
      recentSessionSnippets: new Map(),
      allRuns: [],
      chatModePickerOpen: false,
      newRunFlowOpen: false,
      redistillPanelOpen: false,
      sourceHistoryExpanded: false,
      characterReadinessExpanded: false,
      workSessionPreviewExpanded: false,
      characterOverviewOpen: false,
      currentCharacterOverview: null,
      currentPersonaReview: null,
      currentPersonaAutofill: null,
      currentRelationDetails: null,
      currentSceneCardEditor: null,
      sceneCards: [],
      currentSceneCard: null,
      selectedSceneCardId: "",
      currentSceneCardRecommendation: null,
      currentSelfCardEditor: null,
      selfCards: [],
      currentSelfCard: null,
      selectedSelfCardId: "",
      currentDialogueSceneChainSuggestions: [],
      currentDialogueSceneChainSessionId: "",
      openingPresets: [],
      currentOpeningPreset: null,
      selectedOpeningPresetId: "",
      samplingSuggestion: null,
      redistillSuggestionState: {
        runId: "",
        character: "",
        sourceName: "",
        weakFieldLabels: [],
        items: [],
        selectedSegmentId: "",
        loading: false,
      },
    };
  }

  function createStateStore({ initialState = {}, makeReactive } = {}) {
    const seed = { ...createInitialState(), ...initialState };
    const state = typeof makeReactive === "function" ? makeReactive(seed) : seed;
    const listeners = new Set();

    function assertKnownKey(key) {
      if (!Object.prototype.hasOwnProperty.call(state, key)) {
        throw new Error(`Unknown state key: ${String(key)}`);
      }
    }

    function emit(change) {
      listeners.forEach((listener) => {
        try {
          listener(change, state);
        } catch (error) {
          console.error("state store subscriber failed", error);
        }
      });
    }

    function get(key) {
      assertKnownKey(key);
      return state[key];
    }

    function set(key, value, source = "store") {
      assertKnownKey(key);
      const previous = state[key];
      if (Object.is(previous, value)) {
        return value;
      }
      state[key] = value;
      emit({ type: "set", key, value, previous, source: String(source || "store") });
      return value;
    }

    function patch(partial = {}, source = "store") {
      const changes = [];
      Object.entries(partial || {}).forEach(([key, value]) => {
        assertKnownKey(key);
        const previous = state[key];
        if (Object.is(previous, value)) return;
        state[key] = value;
        changes.push({ key, value, previous });
      });
      if (changes.length) {
        emit({ type: "patch", changes, source: String(source || "store") });
      }
      return state;
    }

    function subscribe(listener, { immediate = false } = {}) {
      if (typeof listener !== "function") {
        return () => {};
      }
      listeners.add(listener);
      if (immediate) {
        listener({ type: "snapshot", source: "subscribe" }, state);
      }
      return () => listeners.delete(listener);
    }

    function getSnapshot() {
      return { ...state };
    }

    function installLegacyBindings(target, keys = Object.keys(state)) {
      if (!target || (typeof target !== "object" && typeof target !== "function")) {
        throw new TypeError("A global-like target is required for legacy state bindings.");
      }
      keys.forEach((key) => {
        assertKnownKey(key);
        const current = Object.getOwnPropertyDescriptor(target, key);
        if (current && !current.configurable) {
          throw new Error(`Cannot install legacy state binding for non-configurable property: ${key}`);
        }
        Object.defineProperty(target, key, {
          configurable: true,
          enumerable: true,
          get: () => state[key],
          set: (value) => set(key, value, `legacy-global:${key}`),
        });
      });
      return target;
    }

    return {
      state,
      get,
      set,
      patch,
      subscribe,
      getSnapshot,
      installLegacyBindings,
      keys: Object.freeze(Object.keys(seed)),
    };
  }

  return { createInitialState, createStateStore };
});
