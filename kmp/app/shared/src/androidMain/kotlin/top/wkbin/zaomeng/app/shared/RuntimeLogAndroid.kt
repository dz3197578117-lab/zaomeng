package top.wkbin.zaomeng.app.shared

import android.content.Context
import top.wkbin.zaomeng.platform.AndroidRuntimeLog

/**
 * 启动运行日志采集（Android）。
 *
 * 薄封装的目的：`androidApp` 只依赖 `:app:shared`，不直接依赖 `:server`，
 * 由这里转一层，后续内部模块调整不会影响应用入口的编译可见性。
 *
 * 必须在 `Application.onCreate` 里尽早调用：采集线程越晚启动，启动阶段
 * （Koin 装配、后端服务图构建）的日志越容易漏掉，而那正是排查启动问题最需要的部分。
 * 重复调用是安全的（内部幂等）。
 */
fun installRuntimeLogCapture(context: Context) {
    AndroidRuntimeLog.install(context)
}
