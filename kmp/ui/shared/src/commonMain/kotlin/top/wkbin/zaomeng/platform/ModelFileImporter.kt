package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.atomicMove
import io.github.vinceglb.filekit.createDirectories
import io.github.vinceglb.filekit.delete
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.div
import io.github.vinceglb.filekit.exists
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.sink
import io.github.vinceglb.filekit.size
import io.github.vinceglb.filekit.source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * 「导入本地模型」的入口句柄：一个按钮（[launch]）+ 一个取消开关（[cancel]）。
 *
 * 端侧推理原先只能通过内置下载器获取模型，手机上已经有 `.gguf` 的用户无路可走。
 * 这里补上最后一段：调系统文件选择器拿文件，流式复制进端侧模型目录。
 */
@Stable
class ModelFileImporter internal constructor(
    private val launchPicker: () -> Unit,
    private val cancelCopy: () -> Unit,
) {
    /** 打开系统文件选择器。 */
    fun launch() = launchPicker()

    /** 取消正在进行的复制。已完成的复制不受影响。 */
    fun cancel() = cancelCopy()
}

/** 一次成功导入的结果。 */
internal data class ImportedModel(
    val fileName: String,
    val sizeBytes: Long,
)

/**
 * 记住一个「导入 GGUF 模型」的选择器。
 *
 * ## 为什么不用 `readBytes()`
 * 同目录下的 [rememberDocumentPicker] / [rememberZipFilePicker] 都是把整个文件读成 `ByteArray`
 * 再回调——插件包只有几 MB，无所谓。但 GGUF 动辄 2–8 GB，那样必然 OOM。所以这里走
 * FileKit 的 `source()` / `sink()` 流式通道，1 MiB 一块地搬。
 *
 * ## 为什么不过滤扩展名
 * GGUF 没有注册 MIME 类型，Android 的 SAF 按 MIME 过滤时很可能把 `.gguf` 直接藏起来。
 * 因此选择器放开为「所有文件」，改由 [sanitizeModelFileName] + 扩展名校验在选中后给出明确提示。
 *
 * @param targetDir 端侧模型目录（取自端侧状态里的 `modelsDir`）。为空表示还没拿到目录，会直接报错。
 * @param onStarted 开始复制时回调，带上文件名与总大小（总大小可能为 0，表示来源未上报大小）。
 * @param onProgress 复制进度回调，已做节流（约 300 ms 一次），不会每个分片都触发重组。
 * @param onSucceeded 复制完成并改名成功后回调。
 * @param onFailed 失败回调，message 可直接展示给用户。
 * @param onCancelled 用户主动取消后回调。
 */
@Composable
fun rememberModelFileImporter(
    targetDir: String,
    onStarted: (fileName: String, totalBytes: Long) -> Unit,
    onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit,
    onSucceeded: (fileName: String, sizeBytes: Long) -> Unit,
    onFailed: (message: String) -> Unit,
    onCancelled: () -> Unit,
): ModelFileImporter {
    val scope = rememberCoroutineScope()

    // 选择器回调是长期存活的，必须每次重组取到最新的入参，否则会用到旧的目录/回调
    val latestTargetDir by rememberUpdatedState(targetDir)
    val latestOnStarted by rememberUpdatedState(onStarted)
    val latestOnProgress by rememberUpdatedState(onProgress)
    val latestOnSucceeded by rememberUpdatedState(onSucceeded)
    val latestOnFailed by rememberUpdatedState(onFailed)
    val latestOnCancelled by rememberUpdatedState(onCancelled)

    var copyJob by remember { mutableStateOf<Job?>(null) }

    val launcher = rememberFilePickerLauncher(type = FileKitType.File()) { picked ->
        if (picked == null) return@rememberFilePickerLauncher
        val directory = latestTargetDir
        if (directory.isBlank()) {
            latestOnFailed("还没拿到端侧模型目录，请先点「刷新状态」再导入。")
            return@rememberFilePickerLauncher
        }
        // 上一次还没搬完就再选一个：直接放弃旧的，避免两个大文件同时写盘
        copyJob?.cancel()
        copyJob = scope.launch {
            try {
                val imported = importIntoDirectory(
                    picked = picked,
                    targetDir = directory,
                    onStarted = latestOnStarted,
                    onProgress = latestOnProgress,
                )
                latestOnSucceeded(imported.fileName, imported.sizeBytes)
            } catch (cancelled: CancellationException) {
                latestOnCancelled()
                throw cancelled
            } catch (error: Throwable) {
                latestOnFailed(error.message ?: "导入失败。")
            }
        }
    }

    return remember(launcher) {
        ModelFileImporter(
            launchPicker = { launcher.launch() },
            cancelCopy = { copyJob?.cancel() },
        )
    }
}

/**
 * 把选中的文件流式复制进 [targetDir]。
 *
 * 先写 `<名字>.gguf.part` 再改名，和内置下载器保持同一套「不留下半截模型」的策略：
 * 中途失败或被取消，目录里只会多一个被 `listModels()` 忽略的 `.part` 文件，
 * 而不会出现一个看起来正常、实际残缺的 `.gguf`（那会让用户在启动时收到莫名其妙的错误）。
 *
 * 抛出的异常交给调用方转成 UI 文案；`CancellationException` 会原样抛出。
 */
internal suspend fun importIntoDirectory(
    picked: PlatformFile,
    targetDir: String,
    onStarted: (fileName: String, totalBytes: Long) -> Unit = { _, _ -> },
    onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
): ImportedModel {
    val rawName = picked.name
    val fileName = sanitizeModelFileName(rawName)
        ?: throw IllegalArgumentException("文件名不合法，无法导入：$rawName")
    if (!fileName.endsWith(GGUF_SUFFIX, ignoreCase = true)) {
        throw IllegalArgumentException("请选择 .gguf 模型文件（当前选中的是 $rawName）。")
    }

    val directory = PlatformFile(targetDir)
    val target = directory / fileName
    if (target.exists()) {
        throw IllegalStateException("同名模型已存在：$fileName。请先删除它，或把文件改名后再导入。")
    }

    // 上一次中断留下的半截文件：先清掉，否则 atomicMove 会因目标已存在而失败
    val partFile = directory / "$fileName$PART_SUFFIX"
    val totalBytes = runCatching { picked.size() }.getOrDefault(0L)

    // 用平台 IO 调度器而非 Dispatchers.IO：后者在 Kotlin/Native 上是 internal
    // （见 :core:runtime 的 PlatformIoDispatcher，Android/JVM 使用 Dispatchers.IO）。
    return withContext(platformIoDispatcher) {
        runCatching { directory.createDirectories() }
        runCatching { partFile.delete(mustExist = false) }
        onStarted(fileName, totalBytes)

        val copied = try {
            picked.source().use { source ->
                partFile.sink().use { sink ->
                    copyWithProgress(source, sink, totalBytes, onProgress)
                }
            }
        } catch (cancelled: CancellationException) {
            runCatching { partFile.delete(mustExist = false) }
            throw cancelled
        } catch (error: Throwable) {
            runCatching { partFile.delete(mustExist = false) }
            throw error
        }

        try {
            partFile.atomicMove(target)
        } catch (error: Throwable) {
            runCatching { partFile.delete(mustExist = false) }
            throw error
        }
        ImportedModel(fileName = fileName, sizeBytes = copied)
    }
}

/**
 * 分片搬运。分片大小与节流间隔都是刻意取的：
 * 1 MiB 在低速来源（云盘挂载）上也能让取消及时生效；300 ms 的节流避免每块都触发一次重组。
 *
 * 每块都调 [ensureActive]——否则 `withContext` 里的紧循环没有任何挂起点，
 * 用户点了「取消」也要等整个文件搬完才会停下来。
 */
internal suspend fun copyWithProgress(
    source: RawSource,
    sink: RawSink,
    totalBytes: Long,
    onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit,
): Long {
    val buffer = Buffer()
    var copied = 0L
    var lastReported = 0L
    var mark = TimeSource.Monotonic.markNow()
    while (true) {
        coroutineContext.ensureActive()
        val read = source.readAtMostTo(buffer, COPY_CHUNK_BYTES)
        if (read == -1L) break
        sink.write(buffer, read)
        copied += read
        if (mark.elapsedNow() >= PROGRESS_INTERVAL) {
            mark = TimeSource.Monotonic.markNow()
            lastReported = copied
            onProgress(copied, totalBytes)
        }
    }
    sink.flush()
    if (copied != lastReported) onProgress(copied, totalBytes)
    return copied
}

/**
 * 把系统选择器给的名字收拾成能安全落盘的文件名，规则与端侧服务里的
 * `OnDeviceModelService.sanitizeFileName` 保持一致（不含路径分隔符、不以点开头、长度受限）。
 * 返回 null 表示这个名字不能用。
 */
internal fun sanitizeModelFileName(raw: String): String? {
    // 某些 provider 会给带目录的显示名，只取最后一段
    val name = raw.trim().substringAfterLast('/').substringAfterLast('\\').trim()
    if (name.isEmpty() || name.length > MAX_FILE_NAME_LENGTH) return null
    if (name.contains('/') || name.contains('\\')) return null
    if (name.contains("..")) return null
    if (name.startsWith('.')) return null
    return name
}

private const val GGUF_SUFFIX = ".gguf"
private const val PART_SUFFIX = ".part"
private const val MAX_FILE_NAME_LENGTH = 200
private const val COPY_CHUNK_BYTES = 1L * 1024 * 1024
private val PROGRESS_INTERVAL = 300.milliseconds
