package top.wkbin.zaomeng.platform

import platform.UIKit.UIApplication
import platform.UIKit.UINavigationController
import platform.UIKit.UITabBarController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow

/**
 * 找到当前窗口栈上最顶层、可直接 present 的 UIViewController。
 * 多窗口（Scene）场景优先取 keyWindow，iOS 13+ 的 keyWindow 属性已废弃但行为一致。
 */
@Suppress("DEPRECATION")
internal fun foregroundViewController(): UIViewController? {
    val application = UIApplication.sharedApplication
    // UIApplication.windows 在 Kotlin/Native 侧是 List<*>（ObjC 声明为
    // NSArray<__kindof UIWindow *> *，带 __kindof 的泛型参数没被映射进来），
    // 直接取元素会推断成 Any?，连带 isKeyWindow / rootViewController 都解析不到，
    // 所以先按类型收窄。注意 isKeyWindow 暴露成「函数」而非「属性」。
    val windows = application.windows.filterIsInstance<UIWindow>()
    val keyWindow = application.keyWindow
        ?: windows.firstOrNull { it.isKeyWindow() }
        ?: windows.firstOrNull()
    var top: UIViewController? = keyWindow?.rootViewController
    while (top != null) {
        val current = top
        top = when {
            current.presentedViewController != null -> current.presentedViewController
            current is UINavigationController -> current.visibleViewController
            current is UITabBarController -> current.selectedViewController
            else -> return current
        }
    }
    return null
}
