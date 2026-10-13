package top.wkbin.zaomeng.ktor.features

import top.wkbin.zaomeng.data.api.KnownFeature

/**
 * 系统域探针。
 *
 * 最重要的一条是 [PromptRulesProbe]：它检查**运行时真正读到的提示词**里有没有
 * 2.1.7 / 2.1.8 那四个关键规则键。读不到就 FAIL 并提示跑 `scripts/check-prompt-sync.sh`——
 * 这能把「改了 `prompts/` 却忘了同步 assets」在用户设备上直接暴露出来
 * （此前只能靠用户反馈「规则没生效」再回头猜）。
 */
internal class SystemProbes {
    /** 提示词加载完整性：运行时能否读到关键规则键。 */
    inner class PromptRulesProbe : BaseProbe(
        id = "system.prompts",
        title = "提示词加载完整性",
        domain = KnownFeature.DOMAIN_SYSTEM,
        features = listOf("system.prompts"),
    ) {
        override suspend fun check(ctx: FeatureProbeContext): ProbeResult {
            val loader = ctx.promptLoader
            val resolved = linkedMapOf<String, String>()
            REQUIRED_PROMPT_RULES.forEach { rule ->
                val text = runCatching {
                    when (rule.source) {
                        // director.yaml 的 force_execute_instruction（2.1.7 强制执行）
                        "director" -> loader.getPromptText("dialogue", "director", rule.key)
                        // turn_system.yaml 的 time_point_persona_rule（2.1.8 时间点人设）
                        "turn_system" -> loader.getTurnSystemRule(rule.key)
                        // distill/guidance.yaml 的 persona_arc_*（2.1.8 人设弧线）
                        "distill_guidance" -> loader.getDistillGuidance()[rule.key].orEmpty()
                        else -> ""
                    }
                }.getOrDefault("")
                resolved["${rule.source}|${rule.key}"] = text
            }
            val assessment = assessPromptRules(resolved)
            // 额外：把每个键读到多少字符也作为证据（0 就是没读到）。
            val sizes = REQUIRED_PROMPT_RULES.joinToString("、") { rule ->
                "${rule.key}=${resolved["${rule.source}|${rule.key}"]?.length ?: 0}"
            }
            return assessment.copy(
                evidence = assessment.evidence + evidenceMapOf(
                    "key_chars" to sizes.take(200),
                    "loader" to ctx.promptLoader::class.simpleName,
                ),
            ).toResult()
        }
    }

    /**
     * 更新检查：**客户端能力**（`ReleaseUpdateChecker` 在 `data:remote`，且只在用户点检查时跑）。
     *
     * 服务端拿不到「最近一次更新检查结果」——那是一次性网络请求，不落盘。
     * 因此这里只能给「无数据」，并说明原因与替代方案，不假装 OK。
     */
    inner class UpdateCheckProbe : BaseProbe(
        id = "system.update_check",
        title = "更新检查",
        domain = KnownFeature.DOMAIN_SYSTEM,
        features = listOf("system.update_check"),
    ) {
        override suspend fun check(ctx: FeatureProbeContext): ProbeResult = ProbeResult.noData(
            "更新检查在客户端执行且结果不落盘，服务端拿不到「最近一次结果」。",
            ProbeEvidence.build { number("known_samples", 0) },
            listOf(
                "现状：ReleaseUpdateChecker.checkForUpdate() 是 data:remote 里的一次性网络请求，结果只回给调用方。",
                "替代：需要新埋点（把检查结果写进客户端偏好或诊断事件）才能在这里显示；本版不做。",
            ),
        )
    }

    /**
     * TTS：绑定状态与播放失败计数。
     *
     * TTS 完全在客户端（`ui:shared` 的 `PlatformTts`）与偏好存储里，
     * 服务端既看不到绑定状态，也没有播放失败计数。因此只能给「无数据」。
     */
    inner class TtsProbe : BaseProbe(
        id = "system.tts",
        title = "TTS 绑定与播放",
        domain = KnownFeature.DOMAIN_SYSTEM,
        features = listOf("system.tts"),
    ) {
        override suspend fun check(ctx: FeatureProbeContext): ProbeResult = ProbeResult.noData(
            "TTS 是纯客户端能力（PlatformTts + 偏好存储），服务端没有绑定状态与失败计数。",
            ProbeEvidence.build { number("known_samples", 0) },
            listOf(
                "现状：TTS 开关/语速存在客户端 DataStore，播放失败只进 cat log，未上报诊断。",
                "替代：需要新埋点（客户端把 TTS 绑定状态与失败计数写进诊断事件）才能在这里显示；本版不做，避免改变客户端行为。",
            ),
        )
    }
}

/** 注册系统域探针。 */
fun registerSystemProbes(registry: FeatureProbeRegistry) {
    val probes = SystemProbes()
    registry.registerAll(
        probes.PromptRulesProbe(),
        probes.UpdateCheckProbe(),
        probes.TtsProbe(),
    )
}

/**
 * 全量注册：**唯一的新增探针挂载点**。
 *
 * 新增功能时的两步（见 `docs/maintainer-setup.md`）：
 * 1. 在 `KnownFeatures.all` 加一条；
 * 2. 在这里（或对应域的 `registerXxxProbes`）注册一个引用该 id 的探针。
 */
fun registerAllFeatureProbes(
    registry: FeatureProbeRegistry,
    plugins: top.wkbin.zaomeng.ktor.services.PluginService,
    storage: top.wkbin.zaomeng.ktor.services.StorageService,
) {
    registerBookshelfProbes(registry)
    registerChatProbes(registry)
    registerPluginProbes(registry, plugins)
    registerModelProbes(registry, storage)
    registerSystemProbes(registry)
}
