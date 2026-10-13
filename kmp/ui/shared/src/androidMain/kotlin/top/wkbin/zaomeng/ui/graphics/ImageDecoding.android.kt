package top.wkbin.zaomeng.ui.graphics

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 两趟解码：先 `inJustDecodeBounds` 只读原始尺寸（**不分配像素**），再按
 * [computeImageSampleSize] 算出的倍数真正解码。
 *
 * 为什么必须下采样——真机崩溃实证：用户把一张 **9216×6144** 的照片设为聊天背景后必崩，
 * 诊断原文是 `Canvas: trying to draw too large(226492416bytes) bitmap.`，
 * 即 `9216 × 6144 × 4 = 216 MB` 的位图超过了 Android `Canvas` 的 100 MB 硬上限。
 * 那条异常抛在**绘制期**（`BitmapPainter.onDraw`），下面这个 `catch` 拦不住它——
 * 只有把解码尺寸降下来才能避免。细节见 `ImageSampling.kt`。
 *
 * 用 Skia 的 JVM/桌面端仍按原样整幅解码：桌面没有 Android 那条 100 MB 的
 * `throwIfCannotDraw` 硬线，且本仓库当前只在 Android 端收到过该崩溃。
 */
actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

    val options = BitmapFactory.Options().apply {
        inSampleSize = computeImageSampleSize(bounds.outWidth, bounds.outHeight)
        // 与 DEFAULT_BYTES_PER_PIXEL 对应；这也是 BitmapFactory 的默认配置，写出来是为了
        // 让「字节数估算」与「实际解码配置」在代码上能对上。
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
} catch (error: Throwable) {
    null
}
