package top.wkbin.zaomeng.app

import android.app.Application
import android.content.Context
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import top.wkbin.zaomeng.app.shared.installRuntimeLogCapture
import top.wkbin.zaomeng.di.AndroidAppPlatform
import top.wkbin.zaomeng.di.sharedAppModules
import top.wkbin.zaomeng.platform.EarlyCrashRecorder

/** Android 应用入口：初始化 Koin（前台服务等组件在进程内通过 Koin 解析依赖）。 */
class ZaomengApplication : Application() {

    /**
     * 最早的崩溃兜底必须装在这里，而不是 [onCreate]：
     * Application 类初始化、Koin 装配、日志采集自身都可能崩，那时 `onCreate` 里的完整捕获链还没装上。
     * 2.4.0 真机连续崩 3 次却零堆栈，根因就是那几次崩溃发生在完整链安装之前。
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        EarlyCrashRecorder.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        // 运行日志采集要在最早期启动：Koin 装配与后端服务图的日志同样要能被抓到
        installRuntimeLogCapture(this)
        startKoin {
            androidLogger()
            modules(sharedAppModules(AndroidAppPlatform(applicationContext)))
        }
    }
}
