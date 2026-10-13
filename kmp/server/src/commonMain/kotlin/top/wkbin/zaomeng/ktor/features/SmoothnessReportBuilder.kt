package top.wkbin.zaomeng.ktor.features

import top.wkbin.zaomeng.data.api.FlickerRegionDto
import top.wkbin.zaomeng.data.api.FlickerReportDto
import top.wkbin.zaomeng.data.api.FrameBucketDto
import top.wkbin.zaomeng.data.api.ImageRequestStatDto
import top.wkbin.zaomeng.data.api.LayoutJitterDto
import top.wkbin.zaomeng.data.api.RecompositionStatDto
import top.wkbin.zaomeng.data.api.SmoothnessReportDto
import top.wkbin.zaomeng.platform.ClientDiagnostics

/**
 * 画面与流畅度报告（R7）。
 *
 * ### 措辞纪律（硬性）
 * 这里产出的每一条都是「观测到的现象 + 数字」，**不是**「检测到 bug」。
 * 闪烁一项只能写「疑似闪烁（某区域 300ms 内变化 7 次）」——截图差分给不出因果，
 * 写下「检测到 bug」等于替用户下了一个我们没有证据的结论。
 *
 * ### 各指标的可信度
 * - **帧指标**：只在真机上有真实值；模拟器/桌面窗口的节拍与手机完全不同。
 * - **重组计数**：优先于截图——过度重组是闪烁最常见的真实根因，且开销可控。
 * - **图片重复请求**：同 URL 在缓存未失效时反复拉取，是「刷新一下就好了」的典型来源。
 * - **疑似闪烁**：默认关闭、低频、**像素只在本地内存比较**，不落盘、不外传、不进导出；
 *   导出里只有「区域 + 变化次数 + 时间」。
 */
object SmoothnessReportBuilder {

    fun build(
        frames: ClientDiagnostics.FrameSnapshot = ClientDiagnostics.frameSnapshot(),
        recompositions: List<ClientDiagnostics.RecompositionStat> = ClientDiagnostics.recompositionStats(),
        imageRequests: List<ClientDiagnostics.ImageRequestStat> = ClientDiagnostics.imageRequestStats(),
        jitter: List<ClientDiagnostics.LayoutJitterStat> = ClientDiagnostics.layoutJitterStats(),
        flicker: ClientDiagnostics.FlickerSnapshot = ClientDiagnostics.flickerSnapshot(),
    ): SmoothnessReportDto {
        val supported = frames.totalFrames > 0 || recompositions.isNotEmpty() || imageRequests.isNotEmpty()
        return SmoothnessReportDto(
            supported = supported,
            totalFrames = frames.totalFrames,
            over16Count = frames.over16,
            over32Count = frames.over32,
            over100Count = frames.over100,
            longestFrameMs = frames.longestMs,
            mainThreadBlockCount = frames.mainThreadBlockCount,
            mainThreadBlocks = frames.mainThreadBlocks.take(MAX_BLOCKS),
            pageBuckets = frames.pageBuckets.take(MAX_BUCKETS).map {
                FrameBucketDto(
                    page = it.page,
                    frames = it.frames,
                    over16 = it.over16,
                    over32 = it.over32,
                    over100 = it.over100,
                    longestMs = it.longestMs,
                )
            },
            recompositions = recompositions.take(MAX_RECOMPOSITIONS).map {
                RecompositionStatDto(
                    name = it.name,
                    count = it.count,
                    maxPerFrame = it.maxPerFrame,
                    lastAt = it.lastAt,
                )
            },
            imageRequests = imageRequests.take(MAX_IMAGES).map {
                ImageRequestStatDto(
                    url = it.url,
                    requests = it.requests,
                    cacheHits = it.cacheHits,
                    duplicateRequests = it.duplicateRequests,
                    lastAt = it.lastAt,
                )
            },
            layoutJitter = jitter.take(MAX_JITTER).map {
                LayoutJitterDto(
                    name = it.name,
                    events = it.events,
                    maxShiftDp = it.maxShiftDp,
                    windowMs = it.windowMs,
                    lastAt = it.lastAt,
                )
            },
            flicker = FlickerReportDto(
                enabled = flicker.enabled,
                supported = flicker.supported,
                sampledFrames = flicker.sampledFrames,
                regions = flicker.regions.take(MAX_FLICKER_REGIONS).map {
                    FlickerRegionDto(
                        region = it.region,
                        changes = it.changes,
                        windowMs = it.windowMs,
                        at = it.at,
                    )
                },
                // 恒 false：像素只在内存里比较，没有写盘调用（见 flicker 采样实现）。
                pixelStoredToDisk = false,
                note = flicker.note.ifBlank {
                    if (!flicker.enabled) {
                        "截图差分默认关闭（开启后也是低频采样；像素只在本地内存比较，不落盘、不外传、不进导出）。"
                    } else {
                        "截图差分已开启；只输出区域 + 变化次数 + 时间，像素不落盘。"
                    }
                },
            ),
            note = buildNote(frames, flicker),
        )
    }

    /**
     * 顶部说明：把「这些数字能不能用来下结论」写清楚。
     *
     * 没有这一句，用户会把「最长帧 48ms」读成「应用有 bug」。
     */
    private fun buildNote(frames: ClientDiagnostics.FrameSnapshot, flicker: ClientDiagnostics.FlickerSnapshot): String {
        val parts = mutableListOf<String>()
        parts += if (frames.totalFrames > 0) {
            "帧指标来自本机采样：掉帧分档 >16.7ms / >32ms / >100ms；最长帧 ${frames.longestMs}ms。"
        } else {
            "帧指标尚无样本（客户端采样未接入或当前平台不支持）；无样本不等于流畅。"
        }
        parts += "主线程阻塞 ${frames.mainThreadBlockCount} 次（阈值以上的当时栈见下）。"
        parts += "重组 / 图片重复请求 / 布局跳动为计数，不是结论。"
        parts += if (flicker.regions.isEmpty()) {
            "疑似闪烁：暂无区域超过变化阈值。"
        } else {
            "疑似闪烁（只描述现象，不判定为缺陷）：" + flicker.regions.take(3).joinToString("；") {
                "${it.region} ${it.windowMs}ms 内变化 ${it.changes} 次"
            }
        }
        parts += "阈值需真机校准；无真机时这些值只能证明采样链路在工作。"
        return parts.joinToString(" ")
    }

    /** 一句话摘要（概览页顶部与复制摘要共用）。 */
    fun headline(report: SmoothnessReportDto): String = when {
        !report.supported -> "画面与流畅度：暂无样本"
        else -> "画面与流畅度：${report.totalFrames} 帧" +
            " · >16.7ms ${report.over16Count}" +
            " · >32ms ${report.over32Count}" +
            " · >100ms ${report.over100Count}" +
            " · 最长 ${report.longestFrameMs}ms" +
            " · 主线程阻塞 ${report.mainThreadBlockCount} 次"
    }

    private const val MAX_BUCKETS = 16
    private const val MAX_BLOCKS = 12
    private const val MAX_RECOMPOSITIONS = 24
    private const val MAX_IMAGES = 24
    private const val MAX_JITTER = 16
    private const val MAX_FLICKER_REGIONS = 12
}
