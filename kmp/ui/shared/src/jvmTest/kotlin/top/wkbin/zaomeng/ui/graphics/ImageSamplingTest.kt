package top.wkbin.zaomeng.ui.graphics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 下采样计算的回归测试。
 *
 * 起因是一次真机崩溃：用户把一张 9216×6144 的照片设为聊天背景，App 在**绘制期**抛
 * `RuntimeException: Canvas: trying to draw too large(226492416bytes) bitmap.`。
 * 那 226,492,416 正是 `9216 × 6144 × 4`。
 *
 * 这些用例的作用是**钉住「不能再改回全分辨率」**：如果谁把下采样去掉或调大上限，
 * 下面第一个用例就会失败。
 */
class ImageSamplingTest {

    /** 与实现里的约定保持一致（测试自己算一份，避免把实现的 bug 当成期望值）。 */
    private fun ceilDiv(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt()

    private fun sampledBytes(width: Int, height: Int, sample: Int, bytesPerPixel: Int = 4): Long =
        ceilDiv(width, sample).toLong() * ceilDiv(height, sample) * bytesPerPixel

    // ------------------------------------------------------------------
    // 真机那次崩溃
    // ------------------------------------------------------------------

    @Test
    fun `real crash image is downsampled eightfold`() {
        val width = 9216
        val height = 6144

        // 先钉住「不处理会怎样」：这正是崩溃信息里的字节数
        assertEquals(226_492_416L, width.toLong() * height * 4L)
        assertTrue(
            width.toLong() * height * 4L > ANDROID_CANVAS_MAX_BITMAP_BYTES,
            "这张图全分辨率解码确实超过了 Canvas 的 100 MB 硬线——这正是崩溃的原因",
        )

        val sample = computeImageSampleSize(width, height)

        assertEquals(8, sample, "9216×6144 应缩到 8 倍（1152×768）")
        assertEquals(3_538_944L, sampledBytes(width, height, sample), "约 3.4 MB，比 216 MB 低两个数量级")
        assertTrue(
            sampledBytes(width, height, sample) < ANDROID_CANVAS_MAX_BITMAP_BYTES,
            "下采样后必须远低于 Canvas 硬线",
        )
    }

    @Test
    fun `long edge drives the sample not the total pixel count`() {
        // 竖图同理：长边是 9216
        assertEquals(8, computeImageSampleSize(6144, 9216))
    }

    // ------------------------------------------------------------------
    // 尺寸边界
    // ------------------------------------------------------------------

    @Test
    fun `image smaller than the target is not downsampled`() {
        assertEquals(1, computeImageSampleSize(800, 600))
        assertEquals(1, computeImageSampleSize(1, 1))
        assertEquals(1, computeImageSampleSize(1920, 1080))
    }

    @Test
    fun `image exactly at the target is not downsampled`() {
        assertEquals(1, computeImageSampleSize(DEFAULT_IMAGE_MAX_DIMENSION, DEFAULT_IMAGE_MAX_DIMENSION))
        assertEquals(1, computeImageSampleSize(DEFAULT_IMAGE_MAX_DIMENSION, 1024))
    }

    @Test
    fun `one pixel over the target doubles the sample`() {
        assertEquals(2, computeImageSampleSize(DEFAULT_IMAGE_MAX_DIMENSION + 1, 1024))
    }

    @Test
    fun `only the exceeded dimension forces downsampling`() {
        assertEquals(2, computeImageSampleSize(4096, 2048))
        assertEquals(2, computeImageSampleSize(2048, 4096))
    }

    @Test
    fun `extreme strip is downsampled by its long edge`() {
        // 100000×1：长边要压到 2048 以内 → 64 倍（1563×1）
        assertEquals(64, computeImageSampleSize(100_000, 1))
        assertEquals(64, computeImageSampleSize(1, 100_000))
    }

    @Test
    fun `invalid dimensions do not crash and do not downsample`() {
        // 读不出边界（损坏图 / 非图片）时交给平台解码去失败，不在这里放大问题
        assertEquals(1, computeImageSampleSize(0, 0))
        assertEquals(1, computeImageSampleSize(0, 1000))
        assertEquals(1, computeImageSampleSize(1000, 0))
        assertEquals(1, computeImageSampleSize(-5, -5))
    }

    @Test
    fun `non positive limits fall back to the defaults instead of crashing`() {
        assertEquals(8, computeImageSampleSize(9216, 6144, maxDimension = 0))
        assertEquals(8, computeImageSampleSize(9216, 6144, maxDimension = -1))
        // bytesPerPixel 非法时按默认 4 处理，结果不变
        assertEquals(8, computeImageSampleSize(9216, 6144, bytesPerPixel = 0))
    }

    // ------------------------------------------------------------------
    // 字节数兜底
    // ------------------------------------------------------------------

    @Test
    fun `byte cap kicks in even when the dimension target is huge`() {
        // 尺寸目标放宽到 20000，单看尺寸 20000×20000 完全合格；是字节上限把它逼下来的
        val sample = computeImageSampleSize(20000, 20000, maxDimension = 20000)
        assertEquals(8, sample)
        val bytes = sampledBytes(20000, 20000, sample)
        assertTrue(bytes <= SAFE_IMAGE_MAX_BYTES, "实际 $bytes 超过上限 $SAFE_IMAGE_MAX_BYTES")
        assertTrue(20000 <= 20000, "这个用例的前提是长边本身没超目标")
    }

    @Test
    fun `impossible byte budget still terminates at the cap`() {
        // 只用来钉住「循环一定终止、不会溢出死循环」；正常路径不会走到这里
        assertEquals(MAX_IMAGE_SAMPLE_SIZE, computeImageSampleSize(9216, 6144, maxBytes = 1L))
    }

    // ------------------------------------------------------------------
    // 性质：任何输入下都返回 2 的幂，且两个约束都被满足
    // ------------------------------------------------------------------

    @Test
    fun `sample is always a power of two and satisfies both limits`() {
        val sizes = listOf(
            1 to 1, 800 to 600, 1920 to 1080, 2048 to 2048, 2049 to 1024,
            4096 to 3072, 6144 to 9216, 9216 to 6144, 12000 to 9000,
            20000 to 20000, 100_000 to 1, 1 to 100_000, 32768 to 32,
        )
        for ((width, height) in sizes) {
            val sample = computeImageSampleSize(width, height)
            assertTrue(sample >= 1, "$width×$height → $sample 必须至少为 1")
            assertEquals(0, sample and (sample - 1), "$width×$height → $sample 不是 2 的幂")
            assertTrue(
                ceilDiv(width, sample) <= DEFAULT_IMAGE_MAX_DIMENSION,
                "$width×$height → $sample 之后长边仍超过目标",
            )
            assertTrue(
                ceilDiv(height, sample) <= DEFAULT_IMAGE_MAX_DIMENSION,
                "$width×$height → $sample 之后长边仍超过目标",
            )
            val bytes = sampledBytes(width, height, sample)
            assertTrue(
                bytes <= SAFE_IMAGE_MAX_BYTES,
                "$width×$height → $sample 之后字节数 $bytes 超过上限 $SAFE_IMAGE_MAX_BYTES",
            )
        }
    }
}
