package top.wkbin.zaomeng.ktor.features

import kotlinx.serialization.json.JsonObject
import top.wkbin.zaomeng.data.api.KnownFeature
import top.wkbin.zaomeng.ktor.services.PluginService

/**
 * 插件域探针。
 *
 * 8 个内置插件的「各自动作最近一次成败」从结构化事件 `plugin.action` / `plugin.action_failed`
 * 的 details 里读（`plugin_id` / `action_id` / `generator_id`）——这些埋点在 2.1.12 之前就有，
 * 不需要新增埋点。
 *
 * 插件宿主与插件服务都是只读使用（`list()` / `isEnabled()`），不会安装、启用或删除任何插件。
 */
internal class PluginProbes(
    private val plugins: PluginService,
) {
    /** 插件安装 / 卸载 / 启用（权限、依赖）。 */
    inner class LifecycleProbe : BaseProbe(
        id = "plugins.lifecycle",
        title = "插件安装 / 卸载 / 启用",
        domain = KnownFeature.DOMAIN_PLUGINS,
        features = listOf("plugins.lifecycle"),
    ) {
        override suspend fun check(ctx: FeatureProbeContext): ProbeResult {
            val listing = runCatching { plugins.list() }.getOrElse { error ->
                return ProbeResult.fail("插件清单读取失败：${FeatureProbeRunner.errorSummary(error)}")
            }
            val items = listing.arr("items").objects()
            val enabled = items.count { it.str("enabled").equals("true", ignoreCase = true) }
            val executable = items.count { it.str("executable").equals("true", ignoreCase = true) }
            val blocked = items.filter {
                it.str("executable").equals("false", ignoreCase = true) &&
                    it.str("status").contains("block", ignoreCase = true)
            }
            val events = ctx.recentEvents().map { it.category to it.message }
            val installFailures = events.count { it.first == "plugin.action_failed" }
            val installed = events.count { it.first == "plugin.installed" }
            val details = blocked.take(10).map {
                "${it.str("id").ifBlank { it.str("plugin_id") }}：不可执行（${it.str("status")} / ${it.str("message").take(120)}）"
            }
            val evidence = evidenceMap(
                "plugins" to items.size.toString(),
                "enabled" to enabled.toString(),
                "executable" to executable.toString(),
                "blocked" to blocked.size.toString(),
                "installed_events" to installed.toString(),
                "action_failures" to installFailures.toString(),
            )
            val built = { evidence.toProbeEvidence() }
            return when {
                items.isEmpty() -> ProbeResult.fail("插件清单为空：连内置插件都没有加载出来。", built())
                blocked.isNotEmpty() -> ProbeResult.warn(
                    "有 ${blocked.size} 个插件不可执行（校验未通过或依赖缺失），已存但不会运行。",
                    built(),
                    details,
                )
                else -> ProbeResult.ok(
                    "插件清单可读：${items.size} 个（启用 $enabled，可执行 $executable）。",
                    built(),
                )
            }
        }
    }

    /**
     * 8 个内置插件各自动作的最近一次成败。
     *
     * 每个内置插件单独一行结论（而不是一个大一统的「插件 OK」），
     * 因为用户报的问题永远是「某个具体插件点了没反应」。
     */
    inner class BuiltinActionsProbe : BaseProbe(
        id = "plugins.builtin_actions",
        title = "内置插件动作",
        domain = KnownFeature.DOMAIN_PLUGINS,
        features = listOf("plugins.builtin_actions"),
    ) {
        override suspend fun check(ctx: FeatureProbeContext): ProbeResult {
            val events = ctx.recentEvents()
            val success = events.filter { it.category == "plugin.action" }
            val failures = events.filter { it.category == "plugin.action_failed" }
            val lines = mutableListOf<String>()
            var withSample = 0
            var failedPlugins = 0
            BUILTIN_PLUGIN_LABELS.forEach { (pluginId, label) ->
                val pluginSuccesses = success.filter { it.details["plugin_id"] == pluginId }
                val pluginFailures = failures.filter { it.details["plugin_id"] == pluginId }
                val lastSuccessIndex = events.indexOfLast { it.category == "plugin.action" && it.details["plugin_id"] == pluginId }
                val lastFailureIndex = events.indexOfLast { it.category == "plugin.action_failed" && it.details["plugin_id"] == pluginId }
                when {
                    lastSuccessIndex < 0 && lastFailureIndex < 0 -> lines += "$label：无记录（还没用过）"
                    lastFailureIndex > lastSuccessIndex -> {
                        failedPlugins++
                        withSample++
                        lines += "$label：最近一次失败（${pluginFailures.lastOrNull()?.message?.take(100)}）"
                    }
                    else -> {
                        withSample++
                        val last = pluginSuccesses.lastOrNull()
                        lines += "$label：最近一次成功（${last?.message?.take(60)}）"
                    }
                }
            }
            val evidence = evidenceMap(
                "builtin_plugins" to BUILTIN_PLUGIN_LABELS.size.toString(),
                "with_sample" to withSample.toString(),
                "failed_plugins" to failedPlugins.toString(),
                "action_success_events" to success.size.toString(),
                "action_failure_events" to failures.size.toString(),
            )
            val built = { evidence.toProbeEvidence() }
            return when {
                failedPlugins > 0 -> ProbeResult.fail(
                    "有 $failedPlugins 个内置插件的最近一次动作失败。",
                    built(),
                    lines,
                )
                withSample == 0 -> ProbeResult.noData(
                    "8 个内置插件都还没有动作记录。",
                    built(),
                    lines,
                )
                else -> ProbeResult.ok(
                    "内置插件动作正常：$withSample/${BUILTIN_PLUGIN_LABELS.size} 个有过记录且最近一次都成功。",
                    built(),
                    lines,
                )
            }
        }
    }

    /**
     * 插件规则引擎 `afterTurn` 是否抛错（会不会影响提交）。
     *
     * 架构事实：`PluginRuleEngine.afterTurn` **没有异常埋点**，它在提交路径上被直接调用；
     * 一旦抛错会连带让本轮提交失败（用户看到「发送失败」）。因此在**不加新埋点**的前提下
     * 只能做两件只读的事：
     * 1. 读会话 manifest 里的 `plugin_rule_states` / `plugin_rule_last_turns`，判断规则是否真的跑过；
     * 2. 读 `dialogue.stream_error` 事件（afterTurn 抛错会连带整个流式回复失败并落这条）。
     */
    inner class RuleEngineProbe : BaseProbe(
        id = "plugins.rule_engine",
        title = "插件规则引擎 afterTurn",
        domain = KnownFeature.DOMAIN_PLUGINS,
        features = listOf("plugins.rule_engine"),
    ) {
        override suspend fun check(ctx: FeatureProbeContext): ProbeResult {
            val declarative = runCatching { plugins.activeDeclarativeRules() }.getOrDefault(emptyList())
            val events = ctx.recentEvents()
            val streamErrors = events.count { it.category == "dialogue.stream_error" }
            val focused = focusSessionsForPlugins(ctx)
            var statesWritten = 0
            var lastTurnsWritten = 0
            focused.forEach { (_, manifest) ->
                if (manifest.obj("plugin_rule_states")?.isNotEmpty() == true) statesWritten++
                if (manifest.obj("plugin_rule_last_turns")?.isNotEmpty() == true) lastTurnsWritten++
            }
            val evidence = evidenceMap(
                "active_rules" to declarative.size.toString(),
                "sessions_with_turns" to focused.size.toString(),
                "states_written" to statesWritten.toString(),
                "last_turns_written" to lastTurnsWritten.toString(),
                "stream_errors" to streamErrors.toString(),
            )
            val built = { evidence.toProbeEvidence() }
            val details = mutableListOf<String>()
            if (declarative.isEmpty()) {
                details += "当前没有启用任何声明式规则（没有规则时 afterTurn 无事可做，属正常）。"
            }
            if (streamErrors > 0) {
                details += "本轮进程内有 $streamErrors 次对话流错误；afterTurn 抛错会连带提交失败并落到这里（但没有 afterTurn 专属事件，无法百分百归因）。"
            }
            return when {
                declarative.isEmpty() -> ProbeResult.noData(
                    "没有启用声明式规则，afterTurn 无样本。",
                    built(),
                    details,
                )
                focused.isEmpty() -> ProbeResult.noData(
                    "有 ${declarative.size} 条规则，但还没有产生轮次的会话，无法判断规则是否执行。",
                    built(),
                    details,
                )
                statesWritten == 0 -> ProbeResult.warn(
                    "有 ${declarative.size} 条规则、${focused.size} 个会话已产生轮次，但没有一个会话写入 plugin_rule_states。",
                    built(),
                    details,
                )
                else -> ProbeResult.ok(
                    "规则引擎已执行：$statesWritten/${focused.size} 个会话写入了规则状态。",
                    built(),
                    details,
                )
            }
        }
    }

    /** 声明式插件校验（插件构建器）。 */
    inner class DeclarativeValidationProbe : BaseProbe(
        id = "plugins.declarative",
        title = "声明式插件校验",
        domain = KnownFeature.DOMAIN_PLUGINS,
        features = listOf("plugins.declarative_validation"),
    ) {
        override suspend fun check(ctx: FeatureProbeContext): ProbeResult {
            val listing = runCatching { plugins.list() }.getOrElse { error ->
                return ProbeResult.fail("插件清单读取失败：${FeatureProbeRunner.errorSummary(error)}")
            }
            val items = listing.arr("items").objects()
            val external = items.filter { it.str("source") != "official" && it.str("source") != "builtin" }
            val invalid = external.filter { item ->
                val status = item.str("status")
                status.contains("invalid", ignoreCase = true) || status.contains("error", ignoreCase = true)
            }
            val executable = external.count { it.str("executable").equals("true", ignoreCase = true) }
            val evidence = evidenceMap(
                "external" to external.size.toString(),
                "executable" to executable.toString(),
                "invalid" to invalid.size.toString(),
                "builtin" to (items.size - external.size).toString(),
            )
            val built = { evidence.toProbeEvidence() }
            val details = invalid.take(10).map {
                "${it.str("id").ifBlank { it.str("plugin_id") }}：${it.str("status")} · ${it.str("message").take(140)}"
            }
            return when {
                external.isEmpty() -> ProbeResult.noData("没有第三方插件包，声明式校验无样本。", built())
                invalid.isNotEmpty() -> ProbeResult.warn(
                    "有 ${invalid.size} 个第三方插件校验未通过（会存下来但不会执行）。",
                    built(),
                    details,
                )
                else -> ProbeResult.ok(
                    "第三方插件 $executable/${external.size} 个通过校验并可执行。",
                    built(),
                )
            }
        }
    }
}

/** 内置插件 id → 中文展示名（与 `BuiltinPlugins.all` 一一对应）。 */
internal val BUILTIN_PLUGIN_LABELS: Map<String, String> = linkedMapOf(
    "com.zaomeng.ai-association" to "AI 联想",
    "com.zaomeng.inner-thoughts" to "角色读心",
    "com.zaomeng.plot-dice" to "剧情骰子",
    "com.zaomeng.random-npc" to "随机 NPC",
    "com.zaomeng.reply-variants" to "多候选回复",
    "com.zaomeng.voice-polish" to "口吻润色",
    "com.zaomeng.reply-as-character" to "代角色回复",
    "com.zaomeng.character-mute" to "人物禁言",
)

/** 插件域用的会话清单（只读；返回 run/session 与 manifest）。 */
private fun focusSessionsForPlugins(ctx: FeatureProbeContext): List<Pair<String, JsonObject>> {
    val result = mutableListOf<Pair<String, JsonObject>>()
    ctx.runIdsForProbe().forEach { runId ->
        ctx.listSessionIds(runId).take(ctx.maxSessionsPerProbe).forEach { sessionId ->
            val manifest = ctx.loadSessionManifest(runId, sessionId) ?: return@forEach
            if ((manifest.intOrNull("turn_count") ?: 0) > 0) result += "$runId/$sessionId" to manifest
        }
    }
    return result
}

/** 注册插件域探针。 */
fun registerPluginProbes(registry: FeatureProbeRegistry, plugins: PluginService) {
    val probes = PluginProbes(plugins)
    registry.registerAll(
        probes.LifecycleProbe(),
        probes.BuiltinActionsProbe(),
        probes.RuleEngineProbe(),
        probes.DeclarativeValidationProbe(),
    )
}
