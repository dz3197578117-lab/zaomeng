package top.wkbin.zaomeng.feature.chapters

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.wkbin.zaomeng.data.api.ConsistencyIssueDto
import top.wkbin.zaomeng.data.api.FinalReviewIssueDto
import top.wkbin.zaomeng.ui.components.AppCard
import top.wkbin.zaomeng.ui.components.AppCardVariant
import top.wkbin.zaomeng.ui.theme.AppSpacing

/**
 * 全书检查：一致性 + 人工终审。
 *
 * 终审的「放行」永远由人勾选，AI 只列问题；这也是为什么 finalApproved 不落盘——
 * 每次打开都要重新确认。
 */
@Composable
internal fun BookReviewSection(
    state: ChaptersUiState,
    onCheckConsistency: () -> Unit,
    onRunFinalReview: () -> Unit,
    onMarkApproved: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
        Text("全书一致性", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            text = "跨章节查时间线、人物状态、伏笔与设定冲突。至少要有两章正文。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onCheckConsistency,
            enabled = !state.checkingConsistency,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.checkingConsistency) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在通读…")
            } else {
                Text("检查全书一致性")
            }
        }
        state.consistency?.let { report ->
            if (report.summary.isNotBlank()) {
                AppCard(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    contentPadding = PaddingValues(AppSpacing.md),
                ) {
                    Text(report.summary, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (report.issues.isEmpty()) {
                Text("没有发现跨章矛盾。", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    "发现 ${report.issues.size} 处",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                report.issues.forEach { issue -> ConsistencyIssueItem(issue) }
            }
        }

        HorizontalDivider()
        Text("人工终审", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            text = "AI 只列问题：逻辑、情感、敏感内容、原创性。能不能发，最后由你勾。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onRunFinalReview,
            enabled = !state.reviewingAll,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.reviewingAll) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在终审…")
            } else {
                Text("开始终审")
            }
        }
        state.finalReview?.let { report ->
            if (report.verdict.isNotBlank()) {
                AppCard(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    contentPadding = PaddingValues(AppSpacing.md),
                ) {
                    Text(report.verdict, style = MaterialTheme.typography.bodySmall)
                }
            }
            FinalReviewGroup("逻辑", report.logic)
            FinalReviewGroup("情感", report.emotion)
            FinalReviewGroup("敏感内容", report.sensitive)
            FinalReviewGroup("原创性", report.originality, note = "只提示套路化与相似桥段，不做抄袭判定。")

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onMarkApproved(!state.finalApproved) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = state.finalApproved, onCheckedChange = onMarkApproved)
                Column(Modifier.weight(1f)) {
                    Text("我已逐项确认，可以发布", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "这一步只能由人来做；每次打开都需要重新确认。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsistencyIssueItem(issue: ConsistencyIssueDto) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        variant = AppCardVariant.Outlined,
        contentPadding = PaddingValues(AppSpacing.md),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                issue.kind.ifBlank { "问题" } +
                    if (issue.chapters.isNotEmpty()) " · " + issue.chapters.joinToString(" / ") else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(issue.detail, style = MaterialTheme.typography.bodySmall)
            if (issue.advice.isNotBlank()) {
                Text(
                    issue.advice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FinalReviewGroup(
    title: String,
    issues: List<FinalReviewIssueDto>,
    note: String = "",
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "$title · ${if (issues.isEmpty()) "没有发现问题" else "${issues.size} 处"}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (note.isNotBlank()) {
            Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        issues.forEach { issue ->
            AppCard(
                modifier = Modifier.fillMaxWidth(),
                variant = AppCardVariant.Outlined,
                contentPadding = PaddingValues(AppSpacing.md),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (issue.quote.isNotBlank()) {
                        Text(
                            "「${issue.quote}」",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(issue.detail, style = MaterialTheme.typography.bodySmall)
                    if (issue.advice.isNotBlank()) {
                        Text(
                            issue.advice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
