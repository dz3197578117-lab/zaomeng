package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 会话 `transcript_start`（已归档条数）的**唯一读取口径**。
 *
 * ## 为什么需要归一
 *
 * 分支会话拿到的是父会话的**完整前缀**（`take(keepFrom)`），不是「归档窗口 + 最近 N 条」。
 * 所以分支的归档偏移必须是 0，否则 `materializeSessionTranscript` 会把父会话的归档拼到
 * 前缀前面，拼出错误的全量。
 *
 * 写入侧（`DialogueAdvancedService` 建分支时）从 2.2.2 起已经显式 `transcript_start = 0`，
 * 但**升级前产生的分支会话仍残留父会话的偏移**——真机上出现过 `transcript_start=101`，
 * 自检探针 `chat.advanced_actions` 因此报了唯一一条 FAIL。
 *
 * ## 为什么在读取侧做，而不改文件
 *
 * 旧数据可能被旧版本再次读取；悄悄改写磁盘会让两个版本的读取结果不一致，且需要扫描全库、
 * 承担写坏文件的风险。这里只做**纯内存归一**：只要 manifest 带分支标记就按 0 处理，
 * 与写入侧的语义完全一致，不产生任何副作用。
 */
fun effectiveTranscriptStart(session: JsonObject): Int {
    if (isBranchSession(session)) return 0
    return session["transcript_start"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0
}

/** 是否为分支会话。沿用写入侧的两个标记，任一命中即是。 */
fun isBranchSession(session: JsonObject): Boolean {
    if (session.containsKey("branch_origin")) return true
    val meta = session["branch_meta"] as? JsonObject ?: return false
    return meta["is_mainline"]?.jsonPrimitive?.booleanOrNull == false
}
