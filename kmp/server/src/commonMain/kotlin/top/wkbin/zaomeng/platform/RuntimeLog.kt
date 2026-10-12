package top.wkbin.zaomeng.platform

import top.wkbin.zaomeng.data.api.RuntimeLogDto

/**
 * 运行期日志来源。
 *
 * 平台差异收拢在实现里：Android 抓本进程 logcat（含 native / 第三方库 / 系统崩溃日志），
 * JVM/Desktop 用进程内缓冲。实现必须自行容错——采集失败只降级（返回 available=false 的快照
 * 并写明 note），不允许抛异常打断诊断导出。
 */
interface RuntimeLogSource {
    /** 采集来源标识，取值见 [RuntimeLogDto] 伴生常量。 */
    val source: String

    /**
     * 取最近 [maxLines] 行的运行日志快照。
     *
     * @param maxLines 期望的最大行数，实现应自行夹紧到合理上限。
     */
    fun snapshot(maxLines: Int = DEFAULT_SNAPSHOT_LINES): RuntimeLogDto

    companion object {
        /** 默认返回行数：约 1500 行，覆盖一次典型会话/蒸馏的关键路径。 */
        const val DEFAULT_SNAPSHOT_LINES = 1_500
    }
}
