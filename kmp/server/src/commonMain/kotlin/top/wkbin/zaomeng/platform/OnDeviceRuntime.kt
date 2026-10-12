package top.wkbin.zaomeng.platform

import okio.Path

/**
 * 端侧推理运行时抽象。
 *
 * 端侧模型（GGUF）走 llama.cpp 的 `llama-server`：它作为**独立原生进程**常驻，
 * 监听 127.0.0.1 上的一个端口，对外暴露 OpenAI 兼容的 `/v1/chat/completions`。
 * 于是上层（LlmClient / 蒸馏 / 对话 / 关系抽取）完全不需要感知「端侧」这件事，
 * 只要把模型档案的 base_url 指向 `http://127.0.0.1:<port>/v1`、并关闭 API Key 校验即可。
 *
 * 平台差异只集中在三件事上，全部收拢到本接口：
 * 1. **可执行文件位置**：Android 只允许对 `/data/app/<pkg>/lib/<abi>/` 下的文件执行
 *    `execve`，所以可执行文件必须以 `lib*.so` 命名放进 `jniLibs/`；JVM 桌面端则回退到
 *    环境变量 / PATH 上系统安装的 `llama-server`。
 * 2. **原生进程派生**：`ProcessBuilder`（Android/JVM 均有）。
 * 3. **端口探测**：`ServerSocket` 分配空闲端口、`Socket` 探测监听状态。
 *
 * 若平台未分发/未安装 llama-server 原生产物，`ServerPlatform.onDeviceRuntime()` 返回 null，
 * UI 侧表现为「当前平台不支持端侧推理」，其余功能不受影响。
 */
interface OnDeviceRuntime {
    /** 该平台是否具备端侧推理能力。 */
    val supported: Boolean

    /** 端侧推理可执行文件绝对路径；未随包分发 / 该 ABI 无产物时为 null。 */
    val serverExecutable: String?

    /** 可执行文件缺失的具体原因（用于 UI 提示）；正常时为 null。 */
    val unavailableReason: String? get() = null

    /** GGUF 模型文件存放目录（真实文件系统路径，**不**走 SQLite 文档存储）。 */
    val modelsDir: Path

    /** 进程工作目录（同时存放日志）。 */
    val runtimeDir: Path

    /**
     * 派生一个长期运行的原生进程，把 stdout+stderr 追加写入 [logFile]。
     *
     * 实现必须**持续读取子进程输出**（不能只 start 完就撒手）：否则管道缓冲区写满后
     * 子进程会阻塞在日志输出上，表现为「模型加载到一半卡死」。
     */
    fun spawn(command: List<String>, logFile: Path): NativeProcessHandle?

    /** 分配一个当前空闲的本地端口；失败返回 null。 */
    fun findFreePort(): Int?

    /** 目标端口是否已有进程在监听（用于判定 llama-server 是否已就绪）。 */
    fun isPortListening(port: Int): Boolean

    /**
     * 把 [url] 下载到 [target]（先写 `<target>.part`，下完再改名，避免界面看到半个模型）。
     *
     * [onProgress] 回传 (已下载字节, 总字节)；总长未知时为 -1。
     * [shouldStop] 由下载循环轮询，返回 true 时抛异常中止——阻塞式读无法靠协程取消打断，
     * 只能这样协作式退出。
     * 失败抛异常，由调用方转成用户可读的错误信息。
     *
     * 放在平台层而不是公共层，是因为端侧推理只存在于 Android / 桌面：两者都用
     * `HttpURLConnection`（会自动跟随 HuggingFace 的 302 跳转）。
     */
    fun download(
        url: String,
        target: Path,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        shouldStop: () -> Boolean = { false },
    )
}

/** 原生进程句柄。 */
interface NativeProcessHandle {
    val pid: Long

    /** 进程是否仍存活。 */
    val isAlive: Boolean

    /** 请求终止进程（SIGTERM 语义；实现内部应兜底强杀）。 */
    fun destroy()
}
