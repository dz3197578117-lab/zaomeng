package top.wkbin.zaomeng.ui.theme

/**
 * 透明度阶梯。
 *
 * 改造前全项目有 27 处 `copy(alpha = ...)`，取值超过 15 种（0.72/0.5/0.92/0.55/0.78/0.9/0.88
 * /0.86/0.82/0.7/0.6/0.4/0.38/0.14…），导致「同样的次要文字在不同页面深浅不一」。
 * 统一收到 5 档：越往上越接近实色。
 */
object AppAlpha {
    /** 禁用态 / 装饰性水印 */
    const val Disabled = 0.38f

    /** 极次要：分隔说明、占位符 */
    const val Subtle = 0.55f

    /** 次要：辅助文字、未选中图标 */
    const val Muted = 0.72f

    /** 较强：正文弱化、描边 */
    const val Strong = 0.88f

    /** 实色 */
    const val Full = 1f
}

/** 遮罩层（对话框 scrim、图片压暗）。 */
object AppScrim {
    const val Light = 0.24f
    const val Medium = 0.48f
    const val Heavy = 0.72f
}
