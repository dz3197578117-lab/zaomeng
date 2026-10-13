package top.wkbin.zaomeng.platform

import top.wkbin.zaomeng.data.api.AppVersionInfoDto
import top.wkbin.zaomeng.data.api.DeviceInfoDto
import top.wkbin.zaomeng.data.api.EnvironmentInfoDto
import top.wkbin.zaomeng.data.api.MemoryInfoDto
import top.wkbin.zaomeng.ktor.GeneratedAppVersion

/**
 * 版本与设备信息（不依赖 Context 的部分）。
 *
 * Android 取 `Build.*` / native 堆；JVM 取系统属性。需要 Context 的系统内存信息
 * （可用内存 / lowMemory）由 [ServerPlatform.environmentInfo] 的 Android 覆写补齐。
 */
expect fun platformDeviceInfo(): DeviceInfoDto

/** 进程堆内存快照（不依赖 Context 的部分）。 */
expect fun platformMemoryInfo(): MemoryInfoDto

/**
 * 组装版本与现场元信息。
 *
 * 版本号唯一来源是 `kmp/gradle.properties`，由 `:server:http` 的
 * `generateHttpAppVersion` 构建期生成 [GeneratedAppVersion]——**严禁**在源码里手写，
 * 否则又会退回「诊断里没有 appVersion、只能靠日志反推用户版本」的老问题。
 */
fun buildEnvironmentInfo(): EnvironmentInfoDto = EnvironmentInfoDto(
    app = AppVersionInfoDto(
        versionName = GeneratedAppVersion.VERSION_NAME,
        versionCode = GeneratedAppVersion.VERSION_CODE,
    ),
    device = platformDeviceInfo(),
    memory = platformMemoryInfo(),
    capturedAt = nowIsoString(),
)
