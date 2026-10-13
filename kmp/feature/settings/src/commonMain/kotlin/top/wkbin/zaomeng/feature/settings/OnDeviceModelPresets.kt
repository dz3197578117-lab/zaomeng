package top.wkbin.zaomeng.feature.settings

/**
 * 端侧模型的**下载源**。
 *
 * HuggingFace 的 `resolve/main` 直链在官方站与镜像站上是**同构**的（只有主机名不同），
 * 所以清单里存的是「仓库 + 文件名」而不是一整条 URL，换源时只换主机名即可。
 */
internal data class OnDeviceDownloadEndpoint(
    val id: String,
    val label: String,
    val baseUrl: String,
    val note: String,
)

/**
 * 「这个模型能干什么」这句话的**来源**。
 *
 * 页面必须能回答「凭什么是这个结论」——所以每个模型都带上发布者、上游基座、以及元数据的
 * **读取时间与读取到的数字**。这些字段全部来自 HuggingFace 兼容 API 的实读，
 * **不是本项目撰写的能力结论**；能力描述的一手来源是上游基座的模型卡，交由用户判断。
 */
internal data class OnDeviceModelProvenance(
    /** 发布该量化仓库的账号（`Qwen` = 官方；`unsloth` 等 = 社区量化）。 */
    val publisher: String,
    /** 是否官方发布（由发布者账号是否为上游组织判定，非人工声称）。 */
    val official: Boolean,
    /** 量化前的上游基座模型仓库。 */
    val baseModel: String,
    /** 读取仓库元数据的日期；下载量等数字都是这一天的快照，会过时。 */
    val metadataReadAt: String,
    /** 读取时的累计下载量——「被广泛使用」这句话的依据就是它。 */
    val downloads: Long,
    val likes: Long,
    /** 该量化仓库最后更新的日期。 */
    val repoUpdatedAt: String,
) {
    /** 上游基座模型卡：能力描述的一手来源（国内直连可能需代理）。 */
    fun baseModelCardUrl(): String = "https://huggingface.co/$baseModel"

    /** 一句话来源，给界面用。 */
    fun summary(): String = buildString {
        append(if (official) "$publisher 官方" else "$publisher（社区量化）")
        append(" · 上游 ").append(baseModel)
        append(" · 元数据 ").append(metadataReadAt).append(" 实读")
        append("（下载 ").append(downloads).append(" · likes ").append(likes)
        append(" · 仓库更新 ").append(repoUpdatedAt).append("）")
    }
}

/**
 * 端侧可选模型（页面上那份「可用的 AI」清单）。
 *
 * ## 为什么清单在客户端
 * 端侧模型就是「下载一个 GGUF 文件」这件事本身，不需要服务端参与；放在客户端意味着
 * 离线也能看到有哪些模型、点一下就能下。
 *
 * ## ⚠️ 数据来源：不要凭命名习惯改
 * 仓库名、**文件名**、字节数与 [OnDeviceModelProvenance] 全部由 HuggingFace 兼容 API 实读
 * （`/api/models/<repo>?blobs=true` 的 `siblings[].rfilename` / `.size`，以及 `author` /
 * `cardData.base_model` / `downloads` / `likes` / `lastModified`），**不是猜的**——
 * 同一批模型里大小写规则并不统一：
 * `unsloth/Qwen3-1.7B-GGUF` 是 `Qwen3-1.7B-Q4_K_M.gguf`，
 * 而 `Qwen/Qwen2.5-3B-Instruct-GGUF` 是 `qwen2.5-3b-instruct-q4_k_m.gguf`。
 * 改清单时必须重新查一次 API，并同步更新 `sizeBytes` 与 `provenance`。
 *
 * ## 挑选标准（以及本清单**不**声称什么）
 * 只收被广泛使用、中文能力明确的 Qwen 系列，参数量 ≤ 4B、Q4 量化后 ≤ 2.4GB
 * （手机上加载模型还要留 8192 上下文的余量）。
 *
 * [OnDeviceModelPreset.note] 里**只写能从文件大小推出来的东西**（体积与内存预期）；
 * **不写跑分、不写「谁更强」**——同尺寸模型之间的优劣必须真机对比，推不出来。
 * 能力层面的说法一律指向 [OnDeviceModelProvenance] 里的上游模型卡。
 */
internal data class OnDeviceModelPreset(
    val id: String,
    val title: String,
    val repo: String,
    val fileName: String,
    val sizeBytes: Long,
    /** 只讲体积/内存这类可由文件大小推出的事实，不含质量结论。 */
    val note: String,
    val provenance: OnDeviceModelProvenance,
) {
    /** 该文件在指定下载源下的 `resolve` 直链。 */
    fun urlFor(endpoint: OnDeviceDownloadEndpoint): String =
        "${endpoint.baseUrl.trimEnd('/')}/$repo/resolve/main/$fileName"

    /** 该量化仓库在指定下载源下的页面（用户可以先去看一眼再下）。 */
    fun repoPageFor(endpoint: OnDeviceDownloadEndpoint): String =
        "${endpoint.baseUrl.trimEnd('/')}/$repo"
}

internal object OnDeviceModelPresets {

    val endpoints: List<OnDeviceDownloadEndpoint> = listOf(
        OnDeviceDownloadEndpoint(
            id = "huggingface",
            label = "HuggingFace 官方",
            baseUrl = "https://huggingface.co",
            note = "官方源；国内网络多数需要代理才能直连。",
        ),
        OnDeviceDownloadEndpoint(
            id = "hf-mirror",
            label = "hf-mirror 镜像",
            baseUrl = "https://hf-mirror.com",
            note = "第三方社区镜像，国内一般可直连；内容与官方同步，但不是官方服务。",
        ),
    )

    /** 默认源：官方优先。想改成默认走镜像，改这一行即可。 */
    val defaultEndpointId: String = "huggingface"

    /** 读取下列元数据的日期（见 [OnDeviceModelProvenance.metadataReadAt]）。 */
    private const val METADATA_READ_AT = "2026-10-13"

    /** 未知 id 一律回落到第一个源，绝不因为一个坏 id 让页面交互失效。 */
    fun endpointById(id: String): OnDeviceDownloadEndpoint =
        endpoints.firstOrNull { it.id == id } ?: endpoints.first()

    private val unslothQwen3 = OnDeviceModelProvenance(
        publisher = "unsloth",
        official = false,
        baseModel = "Qwen/Qwen3-1.7B",
        metadataReadAt = METADATA_READ_AT,
        downloads = 196_564,
        likes = 78,
        repoUpdatedAt = "2025-06-08",
    )

    private val qwen25_3b = OnDeviceModelProvenance(
        publisher = "Qwen",
        official = true,
        baseModel = "Qwen/Qwen2.5-3B-Instruct",
        metadataReadAt = METADATA_READ_AT,
        downloads = 205_251,
        likes = 218,
        repoUpdatedAt = "2024-09-20",
    )

    private val qwen3_4b = OnDeviceModelProvenance(
        publisher = "Qwen",
        official = true,
        baseModel = "Qwen/Qwen3-4B",
        metadataReadAt = METADATA_READ_AT,
        downloads = 565_950,
        likes = 187,
        repoUpdatedAt = "2025-05-21",
    )

    val all: List<OnDeviceModelPreset> = listOf(
        OnDeviceModelPreset(
            id = "qwen3-1.7b-q4_k_m",
            title = "Qwen3-1.7B",
            repo = "unsloth/Qwen3-1.7B-GGUF",
            fileName = "Qwen3-1.7B-Q4_K_M.gguf",
            sizeBytes = 1_107_409_472L,
            note = "推荐起步：约 1.1GB，加载后建议留 3GB 以上可用内存。",
            provenance = unslothQwen3,
        ),
        OnDeviceModelPreset(
            id = "qwen3-1.7b-q8_0",
            title = "Qwen3-1.7B（Q8_0）",
            repo = "unsloth/Qwen3-1.7B-GGUF",
            fileName = "Qwen3-1.7B-Q8_0.gguf",
            sizeBytes = 1_834_426_944L,
            note = "同尺寸、几乎不压缩精度：文件更大、推理更慢，适合短对话。",
            provenance = unslothQwen3,
        ),
        OnDeviceModelPreset(
            id = "qwen2.5-3b-instruct-q4_k_m",
            title = "Qwen2.5-3B-Instruct",
            repo = "Qwen/Qwen2.5-3B-Instruct-GGUF",
            fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
            sizeBytes = 2_104_932_768L,
            note = "均衡：约 2GB，加载后建议留 4GB 以上可用内存。",
            provenance = qwen25_3b,
        ),
        OnDeviceModelPreset(
            id = "qwen3-4b-q4_k_m",
            title = "Qwen3-4B",
            repo = "Qwen/Qwen3-4B-GGUF",
            fileName = "Qwen3-4B-Q4_K_M.gguf",
            sizeBytes = 2_497_280_256L,
            note = "质量优先：约 2.4GB，加载后建议留 6GB 以上可用内存。",
            provenance = qwen3_4b,
        ),
    )

    fun byId(id: String): OnDeviceModelPreset? = all.firstOrNull { it.id == id }
}
