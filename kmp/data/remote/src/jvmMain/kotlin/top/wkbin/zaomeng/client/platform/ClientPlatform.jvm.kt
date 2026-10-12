package top.wkbin.zaomeng.client.platform

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import top.wkbin.zaomeng.platform.RuntimeLogBuffer
import java.util.UUID

actual object ClientLog {
    actual fun d(tag: String, message: String) {
        println("[$tag] $message")
        RuntimeLogBuffer.append('D', tag, message)
    }

    actual fun i(tag: String, message: String) {
        println("[$tag] $message")
        RuntimeLogBuffer.append('I', tag, message)
    }

    actual fun w(tag: String, message: String, throwable: Throwable?) {
        println("[$tag] WARN: $message")
        RuntimeLogBuffer.append('W', tag, message, throwable)
        throwable?.printStackTrace()
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        System.err.println("[$tag] ERROR: $message")
        RuntimeLogBuffer.append('E', tag, message, throwable)
        throwable?.printStackTrace()
    }
}

actual fun clientRandomUuid(): String = UUID.randomUUID().toString()

actual fun createClientHttpEngine(): HttpClientEngine = OkHttp.create()
