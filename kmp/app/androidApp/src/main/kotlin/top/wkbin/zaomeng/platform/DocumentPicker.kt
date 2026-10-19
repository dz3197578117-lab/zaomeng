package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 选择任意文档文件，回调文件名与字节内容（FileKit 三端原生对话框）。
 *
 * ## 为什么取消、读失败也必须回调
 * 调用方（导入页）是「先置忙、再等结果」的用法：它在拉起选择器**之前**就把自己置成
 * 「正在读取文件…」并禁用所有按钮。而 FileKit 在用户按返回取消时只给一个 `null`，
 * 读文件本身也可能抛错（文件被移走、权限被撤销、大文件 OOM）——这两种情况都拿不到字节。
 * 如果静默返回，调用方就再也没有复位路径：界面永远停在转圈的「正在读取文件…」，
 * 三张来源卡与提交按钮一直是灰的，只能退出页面重进。所以两者都必须是显式回调。
 *
 * @param onCancelled 用户取消选择时回调。
 * @param onFailed 文件读不出来时回调，message 可直接展示给用户。
 * @param onPicked 选中并读出内容后回调。**放在最后一个参数**是为了让既有的
 *   `rememberDocumentPicker { name, bytes -> }` 尾随 lambda 调用点原样有效。
 */
@Composable
fun rememberDocumentPicker(
    onCancelled: () -> Unit = {},
    onFailed: (message: String) -> Unit = {},
    onPicked: (name: String, bytes: ByteArray) -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val launcher = rememberFilePickerLauncher(type = FileKitType.File()) { file ->
        if (file == null) {
            onCancelled()
        } else {
            scope.launch {
                runCatching { file.name to file.readBytes() }
                    .onSuccess { (name, bytes) -> onPicked(name, bytes) }
                    .onFailure { error ->
                        // 协程被取消不是「读取失败」：原样抛出，交给结构化并发收尾。
                        if (error is CancellationException) throw error
                        onFailed(error.message?.takeIf(String::isNotBlank) ?: "文件读取失败。")
                    }
            }
        }
    }
    return { launcher.launch() }
}
