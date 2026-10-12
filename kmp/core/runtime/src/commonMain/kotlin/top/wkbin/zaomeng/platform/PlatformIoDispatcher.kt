package top.wkbin.zaomeng.platform

import kotlinx.coroutines.CoroutineDispatcher

/** 平台 IO 调度器（Android/JVM 使用 Dispatchers.IO）。 */
expect val platformIoDispatcher: CoroutineDispatcher