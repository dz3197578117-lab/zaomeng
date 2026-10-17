package top.wkbin.zaomeng.feature.persona

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.zaomeng.platform.PlatformTts
import top.wkbin.zaomeng.platform.SystemTtsVoice
import top.wkbin.zaomeng.platform.VoiceProfile
import top.wkbin.zaomeng.platform.isNekoSpeakEngine
import top.wkbin.zaomeng.platform.listSystemTtsEngines
import top.wkbin.zaomeng.platform.loadSystemTtsVoices
import top.wkbin.zaomeng.platform.orderSystemTtsVoices

/**
 * 音色库：把当前朗读引擎暴露的**全部音色**列出来，逐个试听、一键设为该角色的声音。
 *
 * ## 为什么做成弹层而不是新页面
 *
 * 「给角色挑声音」是编辑人物时的一个动作，不是独立任务。做成页面会迫使用户
 * 「进音色库 → 记住名字 → 返回 → 手动填 `voice_name`」——那正是改造前的问题。
 * 弹层里选完直接写回字段，不离开上下文。
 *
 * ## 为什么音色来自「引擎」而不是内置目录
 *
 * NekoSpeak 这类引擎的音色是它自己管理的（克隆音色、名人音色、社区 Piper 音色），
 * 造梦无法也不需要枚举它的私有文件。标准 `TextToSpeech.voices` 是两边唯一的公共契约：
 * 它能报出来的，就能用 `voice_name` 指定；报不出来的，写进人物包也无效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceLibrarySheet(
    character: String,
    enginePackage: String,
    sampleText: String,
    delivery: VoiceProfile,
    currentVoice: String,
    tts: PlatformTts,
    onPick: (SystemTtsVoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 引擎展示名取不到就回落包名——比只显示「已选引擎」有用，至少能对照系统设置排查。
    val engineLabel = remember(enginePackage) {
        if (enginePackage.isBlank()) {
            ""
        } else {
            runCatching {
                listSystemTtsEngines(context).firstOrNull { it.packageName == enginePackage }?.label
            }.getOrNull().orEmpty().ifBlank { enginePackage }
        }
    }

    var query by rememberSaveable { mutableStateOf("") }
    var reloadKey by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var voices by remember { mutableStateOf<List<SystemTtsVoice>>(emptyList()) }

    val speaking by tts.isSpeaking.collectAsStateWithLifecycle()
    val speakingId by tts.currentSpeakingId.collectAsStateWithLifecycle()

    LaunchedEffect(enginePackage, reloadKey) {
        loading = true
        error = ""
        loadSystemTtsVoices(context, enginePackage)
            .onSuccess { voices = it }
            .onFailure { error = it.message ?: "读取音色失败。" }
        loading = false
    }

    val shown = remember(voices, query) { orderSystemTtsVoices(voices, query) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets.navigationBars },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "音色库",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = buildString {
                            append("为「").append(character).append("」挑一个声音")
                            if (engineLabel.isNotBlank()) append(" · ").append(engineLabel)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = { reloadKey++ },
                    enabled = !loading,
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "重新读取音色")
                }
            }

            Spacer(Modifier.size(10.dp))

            when {
                loading -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        text = "正在向引擎读取音色…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }

                error.isNotBlank() -> VoiceLibraryNotice(
                    title = "读不到音色",
                    description = error,
                )

                enginePackage.isBlank() -> VoiceLibraryNotice(
                    title = "还没选定朗读引擎",
                    description = "当前跟随系统默认引擎。想用 NekoSpeak 的海量音色，请先到「设置 → 朗读与语音」" +
                        "把引擎切成 NekoSpeak，再回来挑声音。",
                )

                voices.isEmpty() -> VoiceLibraryNotice(
                    title = "这个引擎没有报告任何音色",
                    description = if (isNekoSpeakEngine(enginePackage)) {
                        "NekoSpeak 的音色要先在它自己的界面里下载（Pocket-TTS、克隆音色或 Piper 社区音色）。" +
                            "下载完回到这里点右上角刷新。"
                    } else {
                        "引擎当前没有可用音色。确认系统设置里这个引擎已启用，或换一个引擎再试。"
                    },
                )

                else -> {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        singleLine = true,
                        placeholder = { Text("搜索音色名或语言，如 zh / yunxi") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = "共 ${voices.size} 个音色" + if (shown.size != voices.size) "，匹配 ${shown.size} 个" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 460.dp),
                        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(shown, key = { it.name }) { voice ->
                            VoiceRow(
                                voice = voice,
                                selected = voice.name.equals(currentVoice, ignoreCase = true),
                                speaking = speaking && speakingId == previewIdOf(voice.name),
                                onPreview = {
                                    if (speaking && speakingId == previewIdOf(voice.name)) {
                                        tts.stop()
                                    } else {
                                        tts.speak(
                                            id = previewIdOf(voice.name),
                                            text = sampleText,
                                            pitch = delivery.pitch,
                                            speed = delivery.speed,
                                            volume = delivery.volume,
                                            speaker = character,
                                            voiceName = voice.name,
                                        )
                                    }
                                },
                                onPick = { onPick(voice) },
                            )
                        }
                        item(key = "voice-library-footnote") {
                            Text(
                                text = "列表由引擎提供。NekoSpeak 里新下载的音色，点右上角刷新即可出现。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun previewIdOf(voiceName: String): String = "voice-lib-$voiceName"

@Composable
private fun VoiceLibraryNotice(title: String, description: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VoiceRow(
    voice: SystemTtsVoice,
    selected: Boolean,
    speaking: Boolean,
    onPreview: () -> Unit,
    onPick: () -> Unit,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = voice.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(voice.localeLabel)
                        if (voice.qualityLabel.isNotBlank()) append(" · ").append(voice.qualityLabel)
                        if (voice.requiresNetwork) append(" · 需联网")
                        if (voice.isDefault) append(" · 默认")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(onClick = onPreview) {
                if (speaking) {
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = "停止试听",
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "试听",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (selected) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = "当前音色",
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
            } else {
                TextButton(onClick = onPick) { Text("用这个") }
            }
        }
    }
}
