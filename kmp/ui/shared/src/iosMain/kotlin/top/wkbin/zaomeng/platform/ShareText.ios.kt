@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import platform.CoreGraphics.CGRectGetMidX
import platform.CoreGraphics.CGRectGetMidY
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIActivityViewController
import platform.UIKit.popoverPresentationController

/**
 * iOS 系统分享：弹出 UIActivityViewController。
 *
 * iPad 必须提供 popover 锚点（sourceView/sourceRect），否则系统会崩溃。
 * iPhone 上 popoverPresentationController 本身为 nil，下面的 `?.` 会自动跳过，
 * 所以不必再判断 UIUserInterfaceIdiom —— 那个枚举在 Kotlin/Native 绑定里的写法
 * 与预期不一致（UIUserInterfaceIdiomPad 取不到）。
 */
@Composable
actual fun rememberShareText(): (String) -> Unit = { text ->
    foregroundViewController()?.let { presenter ->
        val activityController = UIActivityViewController(
            activityItems = listOf(text),
            applicationActivities = null,
        )
        val bounds = presenter.view.bounds
        activityController.popoverPresentationController?.sourceView = presenter.view
        // 用 CoreGraphics 的 CGRectGetMidX/CGRectGetMidY 直接取视图中心（返回 Double），
        // 这样无需在 useContents 里再访问嵌套结构体字段；CG_INLINE 函数在 Kotlin/Native
        // 绑定里与 CGRectMake 一样可用。
        activityController.popoverPresentationController?.sourceRect = CGRectMake(
            CGRectGetMidX(bounds),
            CGRectGetMidY(bounds),
            1.0,
            1.0,
        )
        presenter.presentViewController(activityController, animated = true, completion = null)
    }
}
