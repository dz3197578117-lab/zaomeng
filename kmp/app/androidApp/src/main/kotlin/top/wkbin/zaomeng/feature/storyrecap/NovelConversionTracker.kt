package top.wkbin.zaomeng.feature.storyrecap

/**
 * 「这一局已经提交过对话转小说」的**进程内**账本。
 *
 * ## 为什么不是页面里的 `remember`
 * 生成跑在 `NovelConversionForegroundService` 里，用户退出复盘页它照样继续。
 * 之前这个状态只存在 `remember { mutableStateOf(false) }` 里：返回再进来按钮又变回
 * 「开始生成」，用户会以为刚才那次没生效，于是再点一次——队列里就排了两遍同一局的生成。
 *
 * ## 为什么不是查磁盘
 * 生成是「排队 → 跑完才落章」，从提交到落章之间没有任何可查的持久标记。所以这是一份
 * 进程内记录：进程被杀后它一并消失，按钮回到「开始生成」，此时服务也早没了，
 * 再点是正确的行为。
 *
 * ## 为什么不把「已提交」当成「不能再来一次」
 * 确实有人就是想再写一版。这条记录只用来改文案与提供「再生成一版」的入口，
 * 不做成硬性禁用。
 */
internal object NovelConversionTracker {
    private val lock = Any()
    private val startedKeys = mutableSetOf<String>()

    fun isStarted(runId: String, sessionId: String): Boolean = synchronized(lock) {
        key(runId, sessionId) in startedKeys
    }

    fun markStarted(runId: String, sessionId: String) {
        synchronized(lock) { startedKeys += key(runId, sessionId) }
    }

    /** 仅测试用：避免用例之间互相影响。 */
    internal fun reset() {
        synchronized(lock) { startedKeys.clear() }
    }

    private fun key(runId: String, sessionId: String): String = "$runId::$sessionId"
}
