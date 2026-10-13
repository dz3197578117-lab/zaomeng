package top.wkbin.zaomeng.ktor

import top.wkbin.zaomeng.ktor.features.KeystoreHealth
import top.wkbin.zaomeng.platform.ServerPlatform

/**
 * 安全存储（Keystore / JVM 本地）健康度探测。
 *
 * **硬线：只记成败与条目数，绝不读取或记录密钥内容。**
 * 本函数只做两件只读的事：
 * 1. `entries()` —— 能否枚举（读不到就是存储坏了）；
 * 2. 对**已存在**的条目做一次 `get` —— 能否读出来。
 *
 * 刻意**不做写入探测**：探针是只读的，「顺手写个测试值」既可能污染用户的 Keystore，
 * 也违反「探针不改数据」的约束。没有条目时 [KeystoreHealth.sampleReadOk] 为 null（无样本）。
 */
internal fun readKeystoreHealth(platform: ServerPlatform): KeystoreHealth {
    val source = when {
        platform.javaClass.name.contains("Android", ignoreCase = true) -> "android-keystore"
        else -> "jvm-local"
    }
    return try {
        val store = platform.secureStore()
        val keys = store.entries().keys.toList()
        val sampleReadOk = if (keys.isEmpty()) {
            null
        } else {
            runCatching { store.get(keys.first()) != null }.getOrDefault(false)
        }
        KeystoreHealth(
            readable = true,
            entryCount = keys.size,
            sampleReadOk = sampleReadOk,
            source = source,
        )
    } catch (error: Throwable) {
        // 异常摘要里只有类型与消息：绝不能把可能的密钥内容带进来。
        KeystoreHealth(
            readable = false,
            entryCount = 0,
            sampleReadOk = null,
            errorSummary = "${error::class.simpleName ?: "Throwable"}: ${error.message.orEmpty().take(160)}",
            source = source,
        )
    }
}
