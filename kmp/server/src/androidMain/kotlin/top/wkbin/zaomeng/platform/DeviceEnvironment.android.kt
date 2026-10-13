package top.wkbin.zaomeng.platform

import android.os.Build
import android.os.Debug
import android.os.Process
import top.wkbin.zaomeng.data.api.DeviceInfoDto
import top.wkbin.zaomeng.data.api.MemoryInfoDto
import java.lang.Runtime as JavaRuntime

actual fun platformDeviceInfo(): DeviceInfoDto = DeviceInfoDto(
    manufacturer = Build.MANUFACTURER.orEmpty(),
    model = Build.MODEL.orEmpty(),
    osName = "Android",
    osRelease = Build.VERSION.RELEASE.orEmpty(),
    apiLevel = Build.VERSION.SDK_INT,
    abis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
    cpuCount = JavaRuntime.getRuntime().availableProcessors(),
    is64Bit = Process.is64Bit(),
)

actual fun platformMemoryInfo(): MemoryInfoDto {
    val runtime = JavaRuntime.getRuntime()
    return MemoryInfoDto(
        heapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
        heapCommittedBytes = runtime.totalMemory(),
        heapMaxBytes = runtime.maxMemory(),
        // native 堆是端侧推理的主要内存去向，但 Java 堆看不到它，必须单独取。
        nativeHeapBytes = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(0L),
        lowMemory = false,
    )
}
