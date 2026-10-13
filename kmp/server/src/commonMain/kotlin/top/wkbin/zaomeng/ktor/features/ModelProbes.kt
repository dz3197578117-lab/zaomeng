package top.wkbin.zaomeng.ktor.features

import top.wkbin.zaomeng.data.api.KnownFeature
import top.wkbin.zaomeng.ktor.services.StorageService

/**
 * 模型与端侧域探针。
 *
 * 三条独立关注点：
 * - **模型配置**：补全 / 连通性（复用 2.1.12 的 `ModelConnectivityLog`，不重复造）；
 * - **端侧推理**：llama-server 状态、端口、上下文长度、GGUF 与推理输出截断；
 * - **密钥存储**：**只记成败，绝不记内容**（硬线）。
 */
internal class ModelProbes(
    private val storage: StorageService,
) {
    /** 模型配置保存 / 校验 / 连通性。 */
    inner class ConfigProbe : BaseProbe(
        id = "models.config",
        title = "模型配置与连通性",
        domain = KnownFeature.DOMAIN_MODELS,
        features = listOf("models.config"),
    ) {
        override suspend fun check(ctx: FeatureProbeContext): ProbeResult {
            val settings = runCatching { storage.readModelSettings() }.getOrNull()
            val activeProfileId = settings?.activeProfileId.orEmpty()
            val active = settings?.profiles?.firstOrNull { it.profileId == activeProfileId }
            val tests = runCatching { ctx.connectivityLog?.recent() }.getOrNull().orEmpty()
            val lastTest = tests.lastOrNull()
            val keyConfigured = active?.let { ctx.apiKeyConfigured(it.profileId.orEmpty()) }
            val provider = active?.provider.orEmpty().trim()
            val model = active?.model.orEmpty().trim()
            val baseUrl = active?.baseUrl.orEmpty().trim()
            val evidence = evidenceMapOf(
                "profiles" to settings?.profiles?.size?.toString(),
                "active_profile" to activeProfileId.ifBlank { "(未配置)" },
                // `configured` 是别处算出来的布尔值，实测在配置完全可用时也会是 false
                // （真机导出：api_key_configured=true 而 configured=false）。
                // 这里只把它当**信息**展示，**不作为判据**。
                "configured" to active?.configured?.toString(),
                "configured_flag_used_as_verdict" to "false",
                "provider" to provider.ifBlank { "(空)" },
                "model" to model.ifBlank { "(空)" },
                "base_url" to baseUrl.ifBlank { "(空)" },
                "api_key_required" to active?.apiKeyRequired?.toString(),
                "api_key_configured" to keyConfigured?.toString(),
                "connectivity_tests" to tests.size.toString(),
                "last_test_success" to lastTest?.success?.toString(),
                "last_test_ms" to lastTest?.latencyMs?.toString(),
            )
            val built = { evidence.toProbeEvidence() }
            val details = mutableListOf<String>()
            lastTest?.let {
                details += "最近一次测试：${it.testedAt} ${it.provider}/${it.model} ${if (it.success) "成功" else "失败"}（${it.latencyMs}ms）${it.message.take(160)}"
            }
            // 「模型名 ↔ 端点是否般配」：只看字段本身（不依赖 configured）。
            val fit = active?.let { assessModelEndpointFit(provider, model, baseUrl) }
            if (fit != null) details += fit.details
            val keyMissing = active?.apiKeyRequired != false && keyConfigured == false
            return when {
                settings == null || settings.profiles.isEmpty() -> ProbeResult.noData(
                    "还没有配置任何模型档案。",
                    built(),
                )
                active == null -> ProbeResult.fail(
                    "active_profile_id=$activeProfileId 在档案列表里找不到（配置指向了不存在的档案）。",
                    built(),
                )
                // 真正会让用户「一句话都发不出去」的是字段缺失，而不是别人算出来的标志位。
                model.isBlank() || provider.isBlank() -> ProbeResult.fail(
                    "当前档案缺关键字段：provider=${provider.ifBlank { "(空)" }}、model=${model.ifBlank { "(空)" }}（请求发不出去）。",
                    built(),
                )
                baseUrl.isBlank() && !provider.equals("local", ignoreCase = true) -> ProbeResult.warn(
                    "当前档案是云端 provider 但没有 base_url（请求多半会被拒或走错端点）。",
                    built(),
                )
                keyMissing -> ProbeResult.warn(
                    "当前档案需要 API Key，但没有读到已配置的 Key（api_key_configured=false）。",
                    built(),
                    details,
                )
                fit != null -> ProbeResult.warn(fit.summary, built(), details)
                lastTest == null -> ProbeResult.noData(
                    "档案字段完整（provider/model/base_url），但还没有「测试连接」记录，连通性未知。",
                    built(),
                )
                !lastTest.success -> ProbeResult.fail(
                    "最近一次连通性测试失败：${lastTest.message.take(160)}",
                    built(),
                    details,
                )
                else -> ProbeResult.ok(
                    "模型配置与连通性正常（最近一次测试 ${lastTest.latencyMs}ms）。",
                    built(),
                    details,
                )
            }
        }
    }

    /**
     * 端侧推理：llama-server 运行状态、端口、GGUF 读取、上下文长度、推理输出截断次数。
     *
     * 截断次数来源：`OnDeviceModelService` 的日志尾部（llama-server 自己打印
     * `truncated` / `context shift` 一类字样）。这是**启发式**，因此只在日志尾部里计数，
     * 在 details 里明确写清口径，不把它当成精确指标。
     */
    inner class OnDeviceProbe : BaseProbe(
        id = "models.on_device",
        title = "端侧推理",
        domain = KnownFeature.DOMAIN_MODELS,
        features = listOf("models.on_device"),
    ) {
        override suspend fun check(ctx: FeatureProbeContext): ProbeResult {
            val snapshot = ctx.onDevice()
            if (snapshot.isEmpty()) {
                return ProbeResult.noData(
                    "当前平台没有端侧推理能力（没有 llama-server 产物）。",
                    ProbeEvidence.build { flag("supported", false) },
                )
            }
            val supported = snapshot["supported"] == "true"
            val available = snapshot["available"] == "true"
            val state = snapshot["state"].orEmpty()
            val port = snapshot["port"]?.toIntOrNull() ?: 0
            val contextSize = snapshot["context_size"]?.toIntOrNull() ?: 0
            val modelFile = snapshot["model_file"].orEmpty()
            val reason = snapshot["unavailable_reason"].orEmpty()
            val logTail = snapshot["log_tail"].orEmpty()
            val truncatedHits = countTruncationHints(logTail)
            val events = ctx.recentEvents()
            val failures = events.count { it.category == "ondevice.failed" || it.category == "ondevice.download_failed" }
            val details = mutableListOf<String>()
            if (reason.isNotBlank()) details += "不可用原因：${reason.take(160)}"
            if (snapshot["message"].orEmpty().isNotBlank()) details += "状态说明：${snapshot["message"].orEmpty().take(160)}"
            if (truncatedHits > 0) {
                details += "日志尾部出现 $truncatedHits 次截断/上下文轮转字样（启发式统计，含 llama-server 的 context shift 提示）。"
            }

            val evidence = evidenceMapOf(
                "supported" to supported.toString(),
                "available" to available.toString(),
                "state" to state.ifBlank { "unknown" },
                "port" to port.takeIf { it > 0 }?.toString(),
                "model_file" to modelFile.takeIf { it.isNotBlank() },
                "context_size" to contextSize.takeIf { it > 0 }?.toString(),
                "context_size_configured" to (contextSize > 0).toString(),
                "truncation_hints" to truncatedHits.toString(),
                "ondevice_failures" to failures.toString(),
            )
            val built = { evidence.toProbeEvidence() }
            return when {
                !supported -> ProbeResult.noData("当前平台不支持端侧推理。", built(), details)
                !available -> ProbeResult.noData(
                    "端侧推理不可用（找不到 llama-server 可执行文件）。",
                    built(),
                    details,
                )
                state == "failed" -> ProbeResult.fail(
                    "端侧推理最近一次启动/运行失败。",
                    built(),
                    details,
                )
                state == "running" && port <= 0 -> ProbeResult.fail(
                    "端侧推理状态是 running，但没有监听端口（进程可能已退出）。",
                    built(),
                    details,
                )
                state == "running" && contextSize <= 0 -> ProbeResult.warn(
                    "端侧推理在运行，但上下文长度未知（无法判断是否会被截断）。",
                    built(),
                    details,
                )
                state == "running" -> ProbeResult.ok(
                    "端侧推理运行中：端口 $port，上下文 $contextSize。",
                    built(),
                    details,
                )
                else -> ProbeResult.noData(
                    "端侧推理未启动（state=$state）；能力本身可用。",
                    built(),
                    details,
                )
            }
        }
    }

    /**
     * 密钥存储读写：**只记成败，绝不记内容**。
     *
     * 只读探测：读一遍安全存储的 entries 计数，并对已存在条目做一次读取。
     * 没有条目时**不做写入探测**——探针是只读的，「顺手写一个测试值」既违反约束，
     * 也可能在用户的 Keystore 里留下垃圾。
     */
    inner class SecureStoreProbe : BaseProbe(
        id = "models.secure_store",
        title = "密钥存储",
        domain = KnownFeature.DOMAIN_MODELS,
        features = listOf("models.secure_store"),
    ) {
        override suspend fun check(ctx: FeatureProbeContext): ProbeResult {
            val health = ctx.keystoreHealth?.invoke()
                ?: return ProbeResult.noData("当前装配没有提供安全存储探测（只读、只记成败）。")
            val evidence = evidenceMapOf(
                "readable" to health.readable.toString(),
                "entries" to health.entryCount.toString(),
                "sample_read_ok" to health.sampleReadOk?.toString(),
                "source" to health.source.takeIf { it.isNotBlank() },
            )
            val built = { evidence.toProbeEvidence() }
            // 注意：这里永远只有布尔与计数，没有任何键名或值。
            return when {
                !health.readable -> ProbeResult.fail(
                    "安全存储读不到（密钥读取会失败；只记成败，不记内容）。",
                    built(),
                    listOf(health.errorSummary.take(200)).filter { it.isNotBlank() },
                )
                health.entryCount == 0 -> ProbeResult.noData(
                    "安全存储可读，但还没有任何密钥条目（未配置 API Key）。",
                    built(),
                )
                health.sampleReadOk == false -> ProbeResult.fail(
                    "安全存储可枚举，但已存在条目读不出来。",
                    built(),
                )
                else -> ProbeResult.ok(
                    "安全存储可读写：${health.entryCount} 个条目（只校验成败，不读取内容）。",
                    built(),
                )
            }
        }
    }
}

/**
 * 统计日志尾部里的「截断/上下文轮转」字样。
 *
 * llama.cpp 在上下文写满时会打印 context shift / truncated 一类提示；这里只是**启发式**，
 * 用于给出「端侧是否发生过截断」的量级，不作为精确指标对外承诺。
 */
internal fun countTruncationHints(logTail: String): Int {
    if (logTail.isBlank()) return 0
    val hints = listOf("truncated", "truncat", "context shift", "n_keep", "context full", "exceeds the context")
    return logTail.lineSequence().count { line ->
        val lower = line.lowercase()
        hints.any { lower.contains(it) }
    }
}

/** 注册模型与端侧域探针。 */
fun registerModelProbes(registry: FeatureProbeRegistry, storage: StorageService) {
    val probes = ModelProbes(storage)
    registry.registerAll(
        probes.ConfigProbe(),
        probes.OnDeviceProbe(),
        probes.SecureStoreProbe(),
    )
}
