package top.wkbin.zaomeng.ui.graphics

/**
 * 图片解码的下采样计算。
 *
 * **纯函数、不依赖任何平台 API**，因此可以在 `jvmTest` 里直接回归——这一点很重要：
 * 这段逻辑的正确性没法靠「在真机上试一次」来保证，只能靠测试钉住。
 *
 * ## 为什么需要它（真机崩溃实证）
 *
 * 用户把一张 **9216×6144**（约 5660 万像素）的照片设为聊天背景后，App 必崩，诊断导出里的原文：
 *
 * ```
 * RuntimeException: Canvas: trying to draw too large(226492416bytes) bitmap.
 *     at android.graphics.RecordingCanvas.throwIfCannotDraw(RecordingCanvas.java:267)
 *     at androidx.compose.ui.graphics.painter.BitmapPainter.onDraw(BitmapPainter.kt:89)
 * ```
 *
 * `9216 × 6144 × 4`（ARGB_8888 每像素 4 字节）= **226,492,416 B ≈ 216 MB**，与崩溃里的数字一致。
 * Android 的 `Canvas` 有一条硬上限（`RecordingCanvas` 的 `MAX_BITMAP_SIZE` = 100 MB），超过就直接抛
 * `RuntimeException`——而且**抛在绘制期**：`BitmapFactory` 解码那步的成功/失败都被
 * `runCatching` 兜住了，**兜不住绘制**。所以必须在**解码时**就把尺寸降下来。
 *
 * ## 两个约束必须同时满足
 *
 * 1. 长边不超过 [DEFAULT_IMAGE_MAX_DIMENSION]（2048 对聊天背景与头像都够）；
 * 2. 解码后的字节数不超过 [SAFE_IMAGE_MAX_BYTES]（32 MB，离 100 MB 的硬线留足余量）。
 *
 * 第 2 条是**兜底**：即使将来有人把尺寸目标调大，也不会重新踩到那条硬线。
 * 同一张图可能同时存在于设置页预览与聊天页（两份）并再上传成 GPU 纹理，所以上限刻意压得低。
 *
 * @param width 原始宽（`inJustDecodeBounds` 读出的 `outWidth`）
 * @param height 原始高
 * @param maxDimension 目标长边上限；非正数回落到默认值（这是给 UI 用的，宁可不缩也不能崩）
 * @param bytesPerPixel 每像素字节数，ARGB_8888 为 4
 * @param maxBytes 解码结果字节数上限
 * @return 2 的幂的 `inSampleSize`，至少为 1
 */
internal fun computeImageSampleSize(
    width: Int,
    height: Int,
    maxDimension: Int = DEFAULT_IMAGE_MAX_DIMENSION,
    bytesPerPixel: Int = DEFAULT_BYTES_PER_PIXEL,
    maxBytes: Long = SAFE_IMAGE_MAX_BYTES,
): Int {
    // 尺寸非法（读不出边界、图片损坏）时不下采样：让平台解码去失败，不在这里放大问题。
    if (width <= 0 || height <= 0) return 1

    val dimensionLimit = if (maxDimension > 0) maxDimension else DEFAULT_IMAGE_MAX_DIMENSION
    val pixelBytes = if (bytesPerPixel > 0) bytesPerPixel else DEFAULT_BYTES_PER_PIXEL
    val byteLimit = if (maxBytes > 0) maxBytes else SAFE_IMAGE_MAX_BYTES

    // BitmapFactory 只会按 2 的幂下采样，所以这里也只返回 2 的幂。
    var sample = 1
    while (sample < MAX_IMAGE_SAMPLE_SIZE) {
        val sampledWidth = ceilDiv(width, sample)
        val sampledHeight = ceilDiv(height, sample)
        val fitsDimension = sampledWidth <= dimensionLimit && sampledHeight <= dimensionLimit
        val fitsBytes = sampledWidth.toLong() * sampledHeight.toLong() * pixelBytes <= byteLimit
        if (fitsDimension && fitsBytes) return sample
        sample = sample shl 1
    }
    return MAX_IMAGE_SAMPLE_SIZE
}

/**
 * 向上取整的除法。
 *
 * 用**向上**取整而不是 `width / sample`：`BitmapFactory` 实际解出的尺寸不会大于这个值
 * （它把尺寸向下取整到 sample 的倍数），所以按它算字节数是**保守**估计——宁可多缩一档，
 * 也不要在边界上少算。全程走 `Long`，避免 `value + divisor - 1` 在接近 `Int.MAX_VALUE` 时溢出。
 */
private fun ceilDiv(value: Int, divisor: Int): Int =
    ((value.toLong() + divisor - 1L) / divisor).toInt()

/** 目标长边上限：2048 对聊天背景与头像都绰绰有余，解码成本也可控。 */
internal const val DEFAULT_IMAGE_MAX_DIMENSION = 2048

/** ARGB_8888 每像素 4 字节。 */
internal const val DEFAULT_BYTES_PER_PIXEL = 4

/**
 * 解码结果字节数上限（32 MB）。
 *
 * Android `Canvas` 的硬上限是 [ANDROID_CANVAS_MAX_BITMAP_BYTES]（100 MB），这里刻意离它很远。
 */
internal const val SAFE_IMAGE_MAX_BYTES = 32L * 1024L * 1024L

/**
 * Android `RecordingCanvas` 的位图硬上限：超过它 `Canvas.drawBitmap` 直接抛
 * `RuntimeException("Canvas: trying to draw too large(...) bitmap.")`。
 *
 * 解码路径不会用到这个值，它在这里是为了**文档与测试基准**——让回归测试能直接断言
 * 「真机崩溃那张图的字节数确实超过了这条线，而下采样后远低于它」。
 */
internal const val ANDROID_CANVAS_MAX_BITMAP_BYTES = 100L * 1024L * 1024L

/** 采样倍数上限，保证循环一定终止。 */
internal const val MAX_IMAGE_SAMPLE_SIZE = 1 shl 16
