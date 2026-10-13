package top.wkbin.zaomeng.data.api

/**
 * 已知功能清单（**单一来源**）。
 *
 * 存在理由：功能自检的探针是「谁注册谁被检查」，因此**漏注册**在界面上表现为
 * 「一片安静」——看起来全绿，其实那项根本没被检查过。把「期望存在的功能」显式写下来，
 * 诊断页才能算出「M 个功能未注册」并把名字摊开给人看。
 *
 * 维护约定（见 `docs/maintainer-setup.md`）：
 * **新增功能时要在本清单与探针注册处各加一条**，否则诊断页会报「未注册」。
 *
 * - [id] 是稳定标识，注册探针时用 `FeatureProbe.features` 引用；改名等于换功能。
 * - 只放「用户可见的能力」，不放内部实现细节；粒度以「坏了用户能感知」为准。
 * - 本文件位于 `core/contracts`，因此服务端（算未注册）与设置页（显示未注册）
 *   共用同一份清单，不存在两份漂移。
 */
@kotlinx.serialization.Serializable
data class KnownFeature(
    val id: String,
    val title: String,
    val domain: String,
) {
    companion object {
        /** 域：书卷与整理。 */
        const val DOMAIN_BOOKS = "书卷与整理"

        /** 域：会话与对话。 */
        const val DOMAIN_CHAT = "会话与对话"

        /** 域：人物与时间线。 */
        const val DOMAIN_PERSONA = "人物与时间线"

        /** 域：插件。 */
        const val DOMAIN_PLUGINS = "插件"

        /** 域：模型与端侧。 */
        const val DOMAIN_MODELS = "模型与端侧"

        /** 域：系统。 */
        const val DOMAIN_SYSTEM = "系统"

        /** 展示顺序（与需求里的域顺序一致）。 */
        val DOMAINS: List<String> = listOf(
            DOMAIN_BOOKS,
            DOMAIN_CHAT,
            DOMAIN_PERSONA,
            DOMAIN_PLUGINS,
            DOMAIN_MODELS,
            DOMAIN_SYSTEM,
        )
    }
}

/**
 * 已知功能总表。
 *
 * 排序即诊断页展示顺序：先按 [KnownFeature.DOMAINS] 分组，组内按本列表顺序。
 */
object KnownFeatures {

    val all: List<KnownFeature> = listOf(
        // ---------------------------------------------------------------- 书卷与整理
        KnownFeature("bookshelf.import", "书卷导入（ZIP / 本地文本）", KnownFeature.DOMAIN_BOOKS),
        KnownFeature("bookshelf.list", "书卷列表与详情", KnownFeature.DOMAIN_BOOKS),
        KnownFeature("bookshelf.chapters", "原文分章与章节索引", KnownFeature.DOMAIN_BOOKS),
        KnownFeature("bookshelf.distill", "人物蒸馏", KnownFeature.DOMAIN_BOOKS),
        KnownFeature("bookshelf.persona_profile", "人物档案", KnownFeature.DOMAIN_BOOKS),
        KnownFeature("bookshelf.persona_arc", "人设弧线", KnownFeature.DOMAIN_BOOKS),
        KnownFeature("bookshelf.relations", "人物关系图", KnownFeature.DOMAIN_BOOKS),
        KnownFeature("bookshelf.original_knowledge", "原文证据 / 知识库", KnownFeature.DOMAIN_BOOKS),
        KnownFeature("bookshelf.redistill", "继续 / 重新整理", KnownFeature.DOMAIN_BOOKS),

        // ---------------------------------------------------------------- 会话与对话
        KnownFeature("chat.session_create", "新增会话", KnownFeature.DOMAIN_CHAT),
        KnownFeature("chat.timeline", "人物时间线推进", KnownFeature.DOMAIN_CHAT),
        KnownFeature("chat.generate", "对话生成", KnownFeature.DOMAIN_CHAT),
        KnownFeature("chat.first_token_latency", "首字延迟", KnownFeature.DOMAIN_CHAT),
        KnownFeature("chat.transcript_integrity", "会话完整性（无重复 key / 计数一致）", KnownFeature.DOMAIN_CHAT),
        KnownFeature("chat.advanced_actions", "导演方案 / 续写建议 / 场景切换 / 分支 / 纠错 / 重写", KnownFeature.DOMAIN_CHAT),
        KnownFeature("chat.long_term_memory", "长期记忆", KnownFeature.DOMAIN_CHAT),
        KnownFeature("chat.world_memory", "世界记忆", KnownFeature.DOMAIN_CHAT),
        KnownFeature("chat.scene_progress", "场景进度与张力", KnownFeature.DOMAIN_CHAT),
        KnownFeature("chat.consistency", "一致性监控", KnownFeature.DOMAIN_CHAT),
        KnownFeature("chat.session_title", "会话标题", KnownFeature.DOMAIN_CHAT),
        KnownFeature("chat.story_recap", "故事回顾", KnownFeature.DOMAIN_CHAT),
        KnownFeature("chat.crossovers", "书卷交叉 / 时间线", KnownFeature.DOMAIN_CHAT),
        KnownFeature("chat.cards", "卡片与场景卡生成", KnownFeature.DOMAIN_CHAT),

        // ---------------------------------------------------------------- 插件
        KnownFeature("plugins.lifecycle", "插件安装 / 卸载 / 启用", KnownFeature.DOMAIN_PLUGINS),
        KnownFeature("plugins.builtin_actions", "8 个内置插件动作", KnownFeature.DOMAIN_PLUGINS),
        KnownFeature("plugins.rule_engine", "插件规则引擎 afterTurn", KnownFeature.DOMAIN_PLUGINS),
        KnownFeature("plugins.declarative_validation", "声明式插件校验", KnownFeature.DOMAIN_PLUGINS),

        // ---------------------------------------------------------------- 模型与端侧
        KnownFeature("models.config", "模型配置保存 / 校验 / 连通性", KnownFeature.DOMAIN_MODELS),
        KnownFeature("models.on_device", "端侧推理（llama-server）", KnownFeature.DOMAIN_MODELS),
        KnownFeature("models.secure_store", "密钥存储读写", KnownFeature.DOMAIN_MODELS),

        // ---------------------------------------------------------------- 系统
        KnownFeature("system.prompts", "提示词加载完整性", KnownFeature.DOMAIN_SYSTEM),
        KnownFeature("system.update_check", "更新检查", KnownFeature.DOMAIN_SYSTEM),
        KnownFeature("system.tts", "TTS 绑定与播放", KnownFeature.DOMAIN_SYSTEM),
    )

    /** 快速查表。 */
    private val byId: Map<String, KnownFeature> = all.associateBy { it.id }

    fun find(id: String): KnownFeature? = byId[id.trim()]

    /** 是否为清单内已知功能（探针写错 feature id 时用来暴露漂移）。 */
    fun isKnown(id: String): Boolean = byId.containsKey(id.trim())

    /** 按域分组，保持声明顺序。 */
    fun byDomain(): List<Pair<String, List<KnownFeature>>> =
        KnownFeature.DOMAINS.map { domain -> domain to all.filter { it.domain == domain } }

    /** 域内展示顺序索引；未知域排最后。 */
    fun domainOrder(domain: String): Int = KnownFeature.DOMAINS.indexOf(domain).let { if (it < 0) Int.MAX_VALUE else it }
}

/** 一个功能自检探针的展示状态（与 `FeatureProbeStatus` 同名同序，避免 UI 依赖服务端类型）。 */
object FeatureProbeStatuses {
    const val OK = "OK"
    const val WARN = "WARN"
    const val FAIL = "FAIL"

    /**
     * 探针本身没坏，但**没有可判定的样本**（例如该功能还没被用过）。
     *
     * 与 [FAIL] 严格区分：把「没数据」显示成 ❌ 会让用户以为功能坏了。
     */
    const val NO_DATA = "NO_DATA"

    /**
     * 探针**主动跳过**：本次无法判定，且原因是环境性的（例如「运行日志采集已关闭」）。
     *
     * 与 [NO_DATA] 的区别是「为什么没样本」是否已知且可恢复；界面上都显示为「—（无样本）」，
     * 但 [FeatureProbeDto.details] 会写明具体原因，且**不参与 FAIL 计数**。
     */
    const val SKIPPED = "SKIPPED"
}

/**
 * 单个探针的自检结果（网络 DTO）。
 *
 * 服务端与客户端共用：`core/contracts` 不依赖服务端，故两端都能引用。
 * [evidence] 是**只放数字/短文本**的键值对，用来支撑 [summary] 的结论；
 * [details] 是给人展开看的细节（每行一条，已截断）。
 */
@kotlinx.serialization.Serializable
data class FeatureProbeDto(
    /** 稳定 id（如 `bookshelf.import`），同一功能可能有多个探针。 */
    val id: String = "",
    /** 一句话功能名。 */
    val title: String = "",
    /** 所属域（见 [KnownFeature]）。 */
    val domain: String = "",
    /** OK / WARN / FAIL / NO_DATA。 */
    val status: String = FeatureProbeStatuses.NO_DATA,
    /** 一句话结论。 */
    val summary: String = "",
    /** 证据数据：键值对，必须是数字或短文本。 */
    val evidence: Map<String, String> = emptyMap(),
    /** 可选细节（展开查看），每行一条。 */
    val details: List<String> = emptyList(),
    /** 该探针覆盖的功能 id（用于计算「已注册 / 未注册」）。 */
    val features: List<String> = emptyList(),
    /** 结果生成时间（读取自最近一次执行）。 */
    val checkedAt: String = "",
    /** 耗时（毫秒），用于判断探针是否真的廉价。 */
    val durationMs: Long = 0,
    /** 单次检查的异常摘要；非空即代表探针自身出错（结论为 FAIL）。 */
    val error: String = "",
)

/** 证据值的上限：键值对只承载短文本，防止探针把正文塞进诊断。 */
object FeatureProbeLimits {
    const val MAX_EVIDENCE_ENTRIES = 16
    const val MAX_EVIDENCE_VALUE_CHARS = 120
    const val MAX_SUMMARY_CHARS = 200
    const val MAX_DETAIL_LINES = 40
    const val MAX_DETAIL_LINE_CHARS = 400
}

/**
 * 功能自检汇总（网络 DTO）。
 *
 * - [items] 是所有**已注册**探针的最近一次结果；
 * - [unregisteredFeatures] 是 [KnownFeatures] 中没有任何探针覆盖的功能（按域分组前的平铺列表）；
 * - [unknownFeatureRefs] 是探针引用了 [KnownFeatures] 里不存在的 id（开发者写错时的漂移信号）。
 */
@kotlinx.serialization.Serializable
data class FeatureProbeReportDto(
    val generatedAt: String = "",
    /** 已注册探针数。 */
    val registeredCount: Int = 0,
    /** 已知功能总数。 */
    val knownFeatureCount: Int = 0,
    /** 已被至少一个探针覆盖的功能总数。 */
    val coveredFeatureCount: Int = 0,
    /** 未注册（无探针覆盖）的功能数。 */
    val unregisteredCount: Int = 0,
    /** 未注册功能（id + 展示名 + 域）。 */
    val unregisteredFeatures: List<KnownFeature> = emptyList(),
    /** 探针结果（按域、按注册顺序）。 */
    val items: List<FeatureProbeDto> = emptyList(),
    /** 结论计数（含 NO_DATA / SKIPPED），便于页面顶部一行展示。 */
    val okCount: Int = 0,
    val warnCount: Int = 0,
    /**
     * 计入「问题」的探针数。
     *
     * **与 `items` 里 `status == FAIL` 的条数同源**（服务端一次算出，界面不再各算一遍），
     * 避免「计数」与「列表」出现不同数字这种最容易被怀疑的假象。
     */
    val failCount: Int = 0,
    val noDataCount: Int = 0,
    /** 因环境原因主动跳过（目前只有「运行日志采集已关闭、无样本」一类）。 */
    val skippedCount: Int = 0,
    /** 探针覆盖到的功能里，那些**同时**被两个以上探针覆盖的（仅信息用途）。 */
    val multiProbeFeatures: List<String> = emptyList(),
    /** 探针引用了未知功能 id（说明注册处与 [KnownFeatures] 漂移）。 */
    val unknownFeatureRefs: List<String> = emptyList(),
    /** 本轮检查总耗时（毫秒）。 */
    val durationMs: Long = 0,
    /** 缓存命中数（探针结果允许缓存最近一次，未重新执行的不计入耗时）。 */
    val cachedCount: Int = 0,
) {
    /** 一句话汇总，页面顶部与复制摘要共用同一口径。 */
    fun headline(): String =
        "已注册 $registeredCount 项探针 · ✅$okCount ⚠️$warnCount ❌$failCount" +
            " · 无数据 $noDataCount · 无样本 $skippedCount · $unregisteredCount/$knownFeatureCount 个功能未注册"
}

/**
 * 计算「未注册」：已知功能里没有任何探针引用的那些。
 *
 * 抽成纯函数是为了可测：注册表、路由、UI 三处口径必须完全一致。
 *
 * @param known 已知功能清单（默认 [KnownFeatures.all]）
 * @param coveredFeatureIds 所有**已注册探针**引用的功能 id（去重前）
 * @return 未注册功能列表（保持 [known] 的声明顺序）
 */
fun computeUnregisteredFeatures(
    coveredFeatureIds: Collection<String>,
    known: List<KnownFeature> = KnownFeatures.all,
): List<KnownFeature> {
    val covered = coveredFeatureIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    return known.filter { it.id !in covered }
}

/**
 * 计算探针引用了但清单里不存在的功能 id（去重、保持出现顺序）。
 *
 * 这是「注册处与清单漂移」的信号：探针写了一个 id，清单里没有对应项，
 * 统计上会同时表现为「该功能未被注册」（若清单里另有相近名字）与这条告警。
 */
fun computeUnknownFeatureRefs(
    coveredFeatureIds: Collection<String>,
    known: List<KnownFeature> = KnownFeatures.all,
): List<String> {
    val knownIds = known.map { it.id }.toSet()
    return coveredFeatureIds.map { it.trim() }
        .filter { it.isNotEmpty() && it !in knownIds }
        .distinct()
}
