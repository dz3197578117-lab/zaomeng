package top.wkbin.zaomeng.app

import android.app.Application
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import top.wkbin.zaomeng.app.shared.installRuntimeLogCapture
import top.wkbin.zaomeng.di.AndroidAppPlatform
import top.wkbin.zaomeng.di.sharedAppModules

/** Android 应用入口：初始化 Koin（前台服务等组件在进程内通过 Koin 解析依赖）。 */
class ZaomengApplication : Application() {
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
