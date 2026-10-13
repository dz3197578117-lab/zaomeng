package top.wkbin.zaomeng.platform

import top.wkbin.zaomeng.data.api.DeviceInfoDto
import top.wkbin.zaomeng.data.api.MemoryInfoDto

actual fun platformDeviceInfo(): DeviceInfoDto {
    val arch = System.getProperty("os.arch").orEmpty()
    return DeviceInfoDto(
        manufacturer = System.getProperty("java.vendor").orEmpty(),
        model = System.getProperty("os.name").orEmpty(),
        osName = System.getProperty("os.name").orEmpty(),
        osRelease = System.getProperty("os.version").orEmpty(),
        apiLevel = 0,
        abis = listOfNotNull(arch.takeIf(String::isNotBlank)),
        cpuCount = Runtime.getRuntime().availableProcessors(),
        is64Bit = arch.contains("64"),
    )
}

actual fun platformMemoryInfo(): MemoryInfoDto {
    val runtime = Runtime.getRuntime()
    return MemoryInfoDto(
        heapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
        heapCommittedBytes = runtime.totalMemory(),
        heapMaxBytes = runtime.maxMemory(),
        nativeHeapBytes = 0,
        // 桌面端没有 ActivityManager 口径的「系统可用内存」，宁可为 0 也不报一个语义不同的数。
        systemAvailableBytes = 0,
        lowMemory = false,
    )
}
