package top.wkbin.zaomeng.ktor.utils

import kotlinx.serialization.Serializable

/**
 * 对话流式 JSON 增量投影器
 *
 * 将流式结构化 JSON 投影为可读的对话消息增量
 * 对应 Python 的 DialogueJsonDeltaProjector
 *
 * ## 增量实现（性能）
 * 早期版本每次 [feed] 都对整个 buffer 重做「speaker 正则 → 逐值解码 → 括号栈全扫」，
 * 单次成本 O(n·m)（n=buffer 长度，m=speaker 匹配数），长回复下总计 O(n²·m)，
 * 在端侧 CPU 推理本就慢的前提下白烧大量 CPU。现改为：
 * - speaker 匹配只扫新增尾部（回退重叠防跨 chunk 切割），按起点去重；
 * - 已闭合的字符串值解码一次后冻结，不再重解；
 * - 对象边界由持久括号/字符串状态机随文本推进顺带产出，O(n) 总计；
 * - message / inner_thought 键位同样只扫尾部。
 *
 * 语义与全量重算逐帧等价：index 仍为 speaker 匹配的出现序；speaker 未闭合/为空、
 * message 键缺失或值为空的帧不投影；对象闭合后落在对象外的键位判为「无此字段」。
 * 唯一可能走回退全量扫描的是「跨 chunk 才凑齐的模式匹配」这一罕见路径。
 */
class DialogueStreamParser(
    private val chunkSize: Int = 24
) {
    private val buffer = StringBuilder()
    private val emittedLengths = mutableMapOf<String, Int>()

    // 正则表达式：匹配 JSON 中的 "speaker": "
    private val speakerPattern = Regex(""""speaker"\s*:\s*"""")
    private val messagePattern = Regex(""""message"\s*:\s*"""")
    private val innerThoughtPattern = Regex(""""inner_thought"\s*:\s*"""")

    /** 一个 speaker 匹配及其投影所需的全部缓存。index = 在全部匹配中的出现序。 */
    private class MatchState(
        val matchStart: Int,
        val valueStart: Int,
        var index: Int = -1,
        var speaker: String = "",
        var speakerComplete: Boolean = false,
        var enclosingBound: Boolean = false,
        /** 跨 chunk 才凑齐的匹配：状态机已越过其起点，回退全量扫描求边界（罕见路径）。 */
        var retro: Boolean = false,
        var objStart: Int = -1,
        var objEnd: Int = -1, // -1 = 对象尚未闭合
        var msgKeyStart: Int = -1, // -1 未找到；-2 已确认不在对象内
        var msgValueStart: Int = -1,
        var msgKeyScannedTo: Int = -1,
        var message: String = "",
        var msgClosed: Boolean = false,
        var innerKeyStart: Int = -1,
        var innerValueStart: Int = -1,
        var innerKeyScannedTo: Int = -1,
        var innerThought: String = "",
        var innerClosed: Boolean = false,
    )

    private val matches = mutableListOf<MatchState>()
    private var matchScanFrom = 0

    /**
     * 持久括号/字符串扫描状态机：随文本推进顺带记录对象边界，替代每次全量重扫。
     * 状态推进规则与早期的 [findObjectBounds] 完全一致（字符串/转义感知）。
     */
    private class ObjectScanner {
        var pos = 0
        private val stack = ArrayDeque<Int>()
        private val openersByStart = HashMap<Int, MutableList<MatchState>>()
        private var inString = false
        private var escaped = false
        private var nextBind = 0

        fun reset() {
            pos = 0
            stack.clear()
            openersByStart.clear()
            inString = false
            escaped = false
            nextBind = 0
        }

        /** 匹配列表被重排后调用：绑定游标作废，按幂等规则重新绑定。 */
        fun invalidateBindCursor() {
            nextBind = 0
        }

        /**
         * 将扫描推进到 [limit]。调用方保证不会跨过尚未发现的匹配起点
         * （由 earliestPending 约束），否则被越过的匹配无法绑定对象起点。
         */
        fun advance(raw: CharSequence, limit: Int, matches: List<MatchState>) {
            val startPos = pos
            var i = pos
            while (i < limit) {
                while (nextBind < matches.size) {
                    val candidate = matches[nextBind]
                    if (candidate.matchStart > i) break
                    nextBind++
                    // 状态机已越过的起点（罕见晚发现匹配）：留给 retro 回退路径
                    if (candidate.matchStart < startPos) continue
                    bind(candidate)
                }
                val c = raw[i]
                if (inString) {
                    if (escaped) escaped = false
                    else when (c) {
                        '\\' -> escaped = true
                        '"' -> inString = false
                    }
                } else when (c) {
                    '"' -> inString = true
                    '{' -> stack.addLast(i)
                    '}' -> if (stack.isNotEmpty()) {
                        val start = stack.removeLast()
                        openersByStart.remove(start)?.forEach { if (it.objEnd < 0) it.objEnd = i + 1 }
                    }
                }
                i++
            }
            pos = i
        }

        private fun bind(m: MatchState) {
            if (m.enclosingBound) return
            m.enclosingBound = true
            // 栈顶即包含该匹配的对象起点；不在任何对象内时为 -1（与 findObjectBounds 一致）
            m.objStart = stack.lastOrNull() ?: -1
            m.msgKeyScannedTo = maxOf(m.objStart, 0)
            m.innerKeyScannedTo = maxOf(m.objStart, 0)
            if (m.objStart >= 0) openersByStart.getOrPut(m.objStart) { mutableListOf() }.add(m)
        }
    }

    private val scanner = ObjectScanner()

    fun reset() {
        buffer.clear()
        emittedLengths.clear()
        matches.clear()
        matchScanFrom = 0
        scanner.reset()
    }

    /**
     * 返回已累积的完整原始输出（供流式结束后解析最终 responses）。
     */
    fun fullContent(): String = buffer.toString()

    /**
     * 喂入新的原始增量数据
     * @return 投影出的事件列表
     */
    fun feed(delta: String): List<StreamEvent> {
        buffer.append(delta)
        updateIncremental()
        val messages = projectMessages()
        val events = mutableListOf<StreamEvent>()

        val visibleMessages = messages.filterIndexed { index, message ->
            index == 0 || messages[index - 1].speaker != message.speaker
        }
        visibleMessages.forEach { message ->
            val index = message.index
            val role = if (message.speaker in setOf("旁白", "场景提示")) "scene" else "assistant"

            // 处理 message 字段
            processField(
                index = index,
                speaker = message.speaker,
                role = role,
                field = "message",
                value = message.message,
                events = events
            )

            // 处理 inner_thought 字段
            if (message.innerThought.isNotEmpty()) {
                processField(
                    index = index,
                    speaker = message.speaker,
                    role = role,
                    field = "inner_thought",
                    value = message.innerThought,
                    events = events
                )
            }
        }

        return events
    }

    private fun processField(
        index: Int,
        speaker: String,
        role: String,
        field: String,
        value: String,
        events: MutableList<StreamEvent>
    ) {
        val key = "$index:$field"
        val emitted = emittedLengths[key] ?: 0

        if (value.length <= emitted) return

        val suffix = value.substring(emitted)
        emittedLengths[key] = value.length

        // 按 chunkSize 切分
        var offset = 0
        while (offset < suffix.length) {
            val end = minOf(offset + chunkSize, suffix.length)
            events.add(
                StreamEvent(
                    index = index,
                    speaker = speaker,
                    role = role,
                    field = field,
                    text = suffix.substring(offset, end)
                )
            )
            offset = end
        }
    }

    // ------------------------------------------------------------------
    // 增量投影
    // ------------------------------------------------------------------

    private fun updateIncremental() {
        val raw = buffer.toString()

        // 1) 发现新的 speaker 匹配：只扫新增尾部，回退重叠防跨 chunk 切割；按起点去重
        val from = maxOf(0, matchScanFrom - SPEAKER_SCAN_OVERLAP)
        if (from < raw.length) {
            var outOfOrder = false
            speakerPattern.findAll(raw, from).forEach { match ->
                val start = match.range.first
                if (matches.any { it.matchStart == start }) return@forEach
                if (matches.isNotEmpty() && start < matches.last().matchStart) outOfOrder = true
                matches += MatchState(matchStart = start, valueStart = match.range.last + 1)
            }
            if (outOfOrder) {
                matches.sortBy { it.matchStart }
                scanner.invalidateBindCursor()
            }
            matchScanFrom = raw.length
        }

        // 2) 解码尚未闭合的 speaker 值（闭合后即冻结，不再重解）
        var earliestPending = raw.length
        for (m in matches) {
            if (m.speakerComplete) continue
            val (value, complete, _) = decodePartialJsonString(raw, m.valueStart)
            m.speaker = value
            m.speakerComplete = complete
            if (!complete) earliestPending = minOf(earliestPending, m.matchStart)
        }

        // 3) 推进括号状态机；不跨过未闭合的 speaker 匹配，否则其对象起点无从绑定
        scanner.advance(raw, minOf(raw.length, earliestPending), matches)
        // 状态机已越过却仍未绑定的匹配（跨 chunk 才凑齐的模式，罕见）：标记回退路径
        for (m in matches) {
            if (!m.enclosingBound && m.matchStart < scanner.pos) m.retro = true
        }

        // 4) 序号 = 全部匹配的出现序（与全量重算一致）
        matches.forEachIndexed { index, m -> m.index = index }

        // 5) message / inner_thought 键位发现（只扫尾部）与值解码（闭合冻结，未闭合重解）
        for (m in matches) {
            if (!m.speakerComplete || m.speaker.isBlank()) continue
            if (m.retro) {
                val (start, end) = findObjectBounds(raw, m.matchStart)
                m.objStart = start
                m.objEnd = if (end >= raw.length) -1 else end
                m.msgKeyScannedTo = maxOf(start, 0)
                m.innerKeyScannedTo = maxOf(start, 0)
            }
            discoverKey(m, raw, messagePattern, isMessage = true)
            discoverKey(m, raw, innerThoughtPattern, isMessage = false)
            // 对象已闭合且键位落在对象外 → 判为无此字段（原语义：takeIf { first < objEnd }）
            if (m.objEnd >= 0) {
                if (m.msgKeyStart >= m.objEnd) {
                    m.msgKeyStart = -2; m.msgValueStart = -1; m.message = ""; m.msgClosed = false
                }
                if (m.innerKeyStart >= m.objEnd) {
                    m.innerKeyStart = -2; m.innerValueStart = -1; m.innerThought = ""; m.innerClosed = false
                }
            }
            if (m.msgValueStart >= 0 && !m.msgClosed) {
                val (value, closed, _) = decodePartialJsonString(raw, m.msgValueStart)
                m.message = value
                m.msgClosed = closed
            }
            if (m.innerValueStart >= 0 && !m.innerClosed) {
                val (value, closed, _) = decodePartialJsonString(raw, m.innerValueStart)
                m.innerThought = value
                m.innerClosed = closed
            }
        }
    }

    /** 在对象范围内发现 message / inner_thought 键位：只扫未扫过的新增尾部。 */
    private fun discoverKey(m: MatchState, raw: String, pattern: Regex, isMessage: Boolean) {
        val keyStart = if (isMessage) m.msgKeyStart else m.innerKeyStart
        if (keyStart != -1) return // 已找到（≥0）或已确认不在对象内（-2）
        val scannedTo = if (isMessage) m.msgKeyScannedTo else m.innerKeyScannedTo
        if (scannedTo < 0) return // 对象起点未知，等状态机绑定
        // 回退重叠只用于兜住跨 chunk 切割的模式，绝不能再跌破对象起点——
        // 否则会扫到上一个对象的同名字键，把别对象的字段值误认成本对象的。
        val objFloor = maxOf(m.objStart, 0)
        val from = maxOf(scannedTo - KEY_SCAN_OVERLAP, objFloor)
        if (from >= raw.length) return
        val found = pattern.find(raw, from) ?: run {
            if (isMessage) m.msgKeyScannedTo = raw.length else m.innerKeyScannedTo = raw.length
            return
        }
        if (isMessage) {
            m.msgKeyStart = found.range.first
            m.msgValueStart = found.range.last + 1
            m.msgKeyScannedTo = raw.length
        } else {
            m.innerKeyStart = found.range.first
            m.innerValueStart = found.range.last + 1
            m.innerKeyScannedTo = raw.length
        }
    }

    private fun projectMessages(): List<ProjectedMessage> {
        val out = mutableListOf<ProjectedMessage>()
        for (m in matches) {
            if (!m.speakerComplete || m.speaker.isBlank()) continue
            if (m.msgKeyStart < 0) continue // 未找到或已确认不在对象内
            if (m.objEnd >= 0 && m.msgKeyStart >= m.objEnd) continue
            if (m.message.isEmpty()) continue
            out += ProjectedMessage(
                index = m.index,
                speaker = m.speaker.trim(),
                message = m.message,
                innerThought = m.innerThought,
            )
        }
        return out
    }

    /**
     * 解码 JSON 字符串（从开引号结束位置开始）
     * @return Triple(解码的字符串, 是否完整, 结束位置)
     */
    private fun decodePartialJsonString(text: String, start: Int): Triple<String, Boolean, Int> {
        val chars = StringBuilder()
        var index = start

        while (index < text.length) {
            val char = text[index]

            // 遇到闭引号，字符串完整
            if (char == '"') {
                return Triple(chars.toString(), true, index + 1)
            }

            // 非转义字符
            if (char != '\\') {
                chars.append(char)
                index++
                continue
            }

            // 转义字符：需要下一个字符
            if (index + 1 >= text.length) break

            val escaped = text[index + 1]
            when (escaped) {
                'u' -> {
                    // Unicode 转义：\uXXXX
                    if (index + 6 > text.length) break
                    val digits = text.substring(index + 2, index + 6)
                    val codePoint = digits.toIntOrNull(16)
                    if (codePoint != null) {
                        chars.append(codePoint.toChar())
                    } else {
                        chars.append('�')
                    }
                    index += 6
                }
                '"' -> { chars.append('"'); index += 2 }
                '\\' -> { chars.append('\\'); index += 2 }
                '/' -> { chars.append('/'); index += 2 }
                'b' -> { chars.append('\b'); index += 2 }
                'f' -> { chars.append('\u000C'); index += 2 }
                'n' -> { chars.append('\n'); index += 2 }
                'r' -> { chars.append('\r'); index += 2 }
                't' -> { chars.append('\t'); index += 2 }
                else -> { chars.append(escaped); index += 2 }
            }
        }

        return Triple(chars.toString(), false, index)
    }

    /**
     * 找到包含指定位置的 JSON 对象的边界（全量扫描，仅用于罕见的 retro 回退路径）。
     * @return Pair(起始位置, 结束位置)
     */
    private fun findObjectBounds(text: String, position: Int): Pair<Int, Int> {
        val stack = mutableListOf<Int>()
        var targetStart = -1
        var inString = false
        var escaped = false

        for (index in text.indices) {
            if (index == position) {
                targetStart = stack.lastOrNull() ?: -1
            }

            if (inString) {
                if (escaped) {
                    escaped = false
                } else when (text[index]) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                continue
            }

            when (text[index]) {
                '"' -> inString = true
                '{' -> stack.add(index)
                '}' -> {
                    if (stack.isNotEmpty()) {
                        val start = stack.removeAt(stack.lastIndex)
                        if (start == targetStart) {
                            return Pair(start, index + 1)
                        }
                    }
                }
            }
        }

        return if (targetStart >= 0) {
            Pair(targetStart, text.length)
        } else {
            Pair(0, text.length)
        }
    }

    private data class ProjectedMessage(
        val index: Int,
        val speaker: String,
        val message: String,
        val innerThought: String
    )

    private companion object {
        /** speaker 模式跨 chunk 切割时的回退重叠（模式本身仅 9 字符，留足余量）。 */
        const val SPEAKER_SCAN_OVERLAP = 32

        /** message / inner_thought 键位发现的回退重叠（覆盖最长模式 14 字符）。 */
        const val KEY_SCAN_OVERLAP = 32
    }
}

/**
 * 流式事件
 */
@Serializable
data class StreamEvent(
    val index: Int,
    val speaker: String,
    val role: String,
    val field: String,
    val text: String,
    val kind: String = "delta",
)
