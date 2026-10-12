package top.wkbin.zaomeng.client.platform

import android.util.Log
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import top.wkbin.zaomeng.platform.RuntimeLogBuffer
import java.util.UUID

actual object ClientLog {
    actual fun d(tag: String, message: String) {
        Log.d(tag, message)
        RuntimeLogBuffer.append('D', tag, message)
    }

    actual fun i(tag: String, message: String) {
        Log.i(tag, message)
        RuntimeLogBuffer.append('I', tag, message)
    }

    actual fun w(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
        RuntimeLogBuffer.append('W', tag, message, throwable)
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
        RuntimeLogBuffer.append('E', tag, message, throwable)
    }
}

actual fun clientRandomUuid(): String = UUID.randomUUID().toString()

actual fun createClientHttpEngine(): HttpClientEngine = OkHttp.create()
