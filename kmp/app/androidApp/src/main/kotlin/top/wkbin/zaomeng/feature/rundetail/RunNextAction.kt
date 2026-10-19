package top.wkbin.zaomeng.feature.rundetail

import top.wkbin.zaomeng.data.api.RunManifestDto

internal enum class RunNextActionTarget {
    ResumeDistillation,

    /** 沿用原人物与正文整本重跑（书卷详情里带确认框的那条路径）。 */
    RestartDistillation,
    OpenRedistill,
    OpenPersona,
    OpenRelations,
    OpenSessions,
    OpenChapters,
}

internal data class RunNextAction(
    val title: String,
    val description: String,
    val label: String,
    val target: RunNextActionTarget,
    val character: String = "",
)

/**
 * 关系图谱「已经跑过但没成」（失败/被停止），需要重跑。
 *
 * 与「还没跑过」（空白、`pending`）区分开：后者人物档案通常还没校对完，
 * 先把人推去校对人物是对的；但图谱已经失败时再让人去校对人物等于让用户做无用功。
 */
internal fun graphNeedsRestart(graphStatus: String): Boolean =
    graphStatus == "failed" || graphStatus == "stopped"

/**
 * Selects the single most useful next step for a book volume.
 *
 * Mobile users should not have to infer the correct workflow from a long list
 * of tools. This deliberately prefers unfinished work over optional browsing.
 */
internal fun nextActionFor(run: RunManifestDto): RunNextAction {
    if (run.isOriginal) {
        return RunNextAction(
            title = "直接开始写",
            description = "这是原创书卷，不需要蒸馏。去章节工作台写一句设定，先生成大纲再逐章成稿。",
            label = "开始写",
            target = RunNextActionTarget.OpenChapters,
        )
    }

    val completedCharacters = run.progress.completedCharacters.toSet()
    val unfinishedCount = run.lockedCharacters.count { it !in completedCharacters }
    val characters = run.availableCharacters
    val graphStatus = run.progress.graphStatus.ifBlank { run.summary.graphStatus }

    if (run.status == "running") {
        return RunNextAction(
            title = "蒸馏正在进行",
            description = run.progress.currentCharacter
                .takeIf(String::isNotBlank)
                ?.let { "正在整理 $it，完成后可继续校对人物与关系。" }
                ?: "任务会在本页持续更新；完成后可继续校对人物与关系。",
            label = "查看会话",
            target = RunNextActionTarget.OpenSessions,
        )
    }

    if (unfinishedCount > 0) {
        return RunNextAction(
            title = "还有 $unfinishedCount 位人物待完成",
            description = "保留已经生成的资料，只继续处理尚未完成的人物。",
            label = "继续蒸馏",
            target = RunNextActionTarget.ResumeDistillation,
        )
    }

    if (characters.isEmpty()) {
        return RunNextAction(
            title = "先生成可用人物",
            description = "书卷还没有人物档案；可调整人物名单或换入新书段后开始蒸馏。",
            label = "开始蒸馏",
            target = RunNextActionTarget.OpenRedistill,
        )
    }

    val firstCharacter = characters.first()

    if (graphNeedsRestart(graphStatus)) {
        return RunNextAction(
            title = "关系图谱没有跑完",
            description = "人物资料已经在书卷里了；沿用原人物与正文重跑一遍就能补齐关系图谱，不必重新导入正文。",
            label = "重跑一遍",
            target = RunNextActionTarget.RestartDistillation,
        )
    }

    if (graphStatus != "complete") {
        return RunNextAction(
            title = "先校对人物档案",
            description = "先确认 $firstCharacter 的身份、目标与说话方式，再继续查看关系与会话。",
            label = "校对 $firstCharacter",
            target = RunNextActionTarget.OpenPersona,
            character = firstCharacter,
        )
    }

    return RunNextAction(
        title = "人物与关系已准备好",
        description = "关系图已生成；可先检查人物关系，再开始一段场景互动。",
        label = "校对关系",
        target = RunNextActionTarget.OpenRelations,
    )
}
