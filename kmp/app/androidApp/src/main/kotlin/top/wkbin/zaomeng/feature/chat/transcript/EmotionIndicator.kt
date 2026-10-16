package top.wkbin.zaomeng.feature.chat.transcript

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.wkbin.zaomeng.platform.classifySpeechEmotion

@Composable
fun EmotionBadge(innerThought: String?, modifier: Modifier = Modifier) {
    val emoji = classifyEmotion(innerThought.orEmpty()) ?: return
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 2.dp,
    ) {
        Text(text = emoji, fontSize = 11.sp, lineHeight = 12.sp)
    }
}

/**
 * 徽章只需要一个表情。
 *
 * 情绪识别的**实现上移到 `platform` 的 [classifySpeechEmotion]**：朗读韵律（`SpeechVoice`）
 * 与界面徽章从此共用同一套关键词表。原本两处各写一份，改一处就会漂移——
 * 表现为「徽章显示愤怒，声音却是平静的」这种自相矛盾。
 */
internal fun classifyEmotion(text: String): String? = classifySpeechEmotion(text)?.emoji
