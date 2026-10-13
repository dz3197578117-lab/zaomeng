package top.wkbin.zaomeng.ktor.services

/**
 * 人物名归一化（纯函数，放在 :server:storage 供 :server / :server:llm / :server:storage 共用）。
 *
 * 线上问题：模型提取人物名单时经常返回「字符串里再套一层引号」的元素，例如
 * `["\"李慧英\"", "\"陆城\""]`。引号被当成名字的一部分存进 run manifest / 会话参与者后，
 * `"陆城"` 与 `陆城` 被当作两个不同角色：同一个角色会同时出现在
 * `allowed_responders`（带引号那条）与 `forbidden_responders`（干净那条），
 * 模型输出的干净名字被判为非法，触发 `dialogue.parse_failed` 重试，
 * 界面上的说话人标签也会直接显示成 `"李慧英"`。
 *
 * 所以所有「人物名进入系统」「人物名参与比较/展示」的入口都要过这里。
 *
 * 归一化规则（保守处理，避免误伤合法名字）：
 * - 去掉首尾空白；
 * - 反复剥掉**首尾成对**的包裹引号/括号（`"x"`、`'x'`、`“x”`、`『x』`、`《x》` 等）；
 *   只出现单侧引号时不剥（`"陆城` 保持原样），名字中间的引号一律保留；
 * - 去掉「1. 」「2、」这类序号前缀；
 * - 归一化后为空说明它不是一个名字，由调用方过滤。
 */
object CharacterNameNormalizer {
    /** 允许成对剥除的包裹字符（open to close）。 */
    private val WRAPPER_PAIRS = listOf(
        '"' to '"',
        '\'' to '\'',
        '“' to '”',
        '‘' to '’',
        '「' to '」',
        '『' to '』',
        '【' to '】',
        '《' to '》',
        '[' to ']',
        '（' to '）',
        '(' to ')',
        '`' to '`',
    )

    private val LIST_PREFIX = Regex("^\\d+\\s*[.、)）]\\s*")

    /** 归一化一个名字。输入与输出都是纯字符串，不做任何 I/O。 */
    fun normalize(raw: String): String {
        var name = raw.trim()
        var changed = true
        while (changed) {
            changed = false
            if (name.length < 2) break
            val open = name.first()
            val close = name.last()
            if (WRAPPER_PAIRS.any { (pairOpen, pairClose) -> pairOpen == open && pairClose == close }) {
                name = name.substring(1, name.length - 1).trim()
                changed = true
            }
        }
        return name.replace(LIST_PREFIX, "").trim()
    }

    /**
     * 批量归一化：丢弃归一化后的空名，并按归一化结果去重（保留首次出现的顺序）。
     * 同一角色的带引号写法与干净写法只会留下一个，避免同一身份在两个名单里重复出现。
     */
    fun normalizeNames(names: List<String>, limit: Int = Int.MAX_VALUE): List<String> =
        names.map(::normalize).filter(String::isNotEmpty).distinct().take(limit)

    /**
     * 身份键：判断两个写法是不是「同一个人」（名单去重、speaker 匹配、允许/禁止名单取差集）。
     *
     * 在 [normalize] 基础上再忽略空白与大小写。只用于比较，不用于展示或落盘，
     * 因此不会删掉名字中间的引号、也不会改变已存数据的写法。
     */
    fun identityKey(raw: String): String =
        normalize(raw).filterNot(Char::isWhitespace).lowercase()

    /** 名单碎片里可能残留的 JSON 结构字符（`[`、`]`）与包裹引号/括号。 */
    private val LIST_FRAGMENT_NOISE = charArrayOf(
        '[', ']', '{', '}',
        '"', '\'', '“', '”', '‘', '’', '「', '」', '『', '』',
        '【', '】', '《', '》', '(', ')', '（', '）', '`',
    )

    /**
     * 解析「把 JSON 数组当字符串按逗号切分」留下的名单碎片，例如
     * `["李慧英"`、`"陆城"]`、`『李慧英』`。
     *
     * 这类碎片的首尾引号并不成对（一侧是 `[`、另一侧是 `"`），[normalize] 按规则不会处理，
     * 所以这里额外剥掉两侧的 JSON 结构字符与包裹符号。**只用于名单解析的回退路径**
     * （模型没有输出合法 JSON 时的分隔符拆分），不用于展示或落盘。
     */
    fun normalizeListFragment(raw: String): String =
        normalize(raw.trim(*LIST_FRAGMENT_NOISE).trim())
}
