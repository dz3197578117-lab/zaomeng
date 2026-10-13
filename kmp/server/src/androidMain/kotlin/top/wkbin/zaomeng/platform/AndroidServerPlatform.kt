package top.wkbin.zaomeng.platform

import android.content.Context
import androidx.room3.RoomDatabase
import okio.Path
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.backend.ModelApiKeyStore
import top.wkbin.zaomeng.db.ZaomengDatabase
import top.wkbin.zaomeng.db.getDatabaseBuilder
import java.io.File

/** Android 实现：数据目录 = filesDir/zaomeng，提示词走 assets+文件系统回退，密钥走 Keystore。 */
class AndroidServerPlatform(
    context: Context,
    override val promptSource: PromptSource = AndroidPromptSource(context.applicationContext),
) : ServerPlatform {
    private val appContext = context.applicationContext

    /** 平台对象在 startKoin 时创建，与进程启动几乎同时，用它近似进程运行时长。 */
    private val startedAtMillis = System.currentTimeMillis()

    override val dataRoot: Path =
        File(appContext.filesDir, "zaomeng").toOkioPath()

    private val store = ModelApiKeyStore(appContext)

    private val onDeviceRuntime = AndroidOnDeviceRuntime(appContext)

    override fun secureStore(): SecureKeyValueStore = store

    override fun databaseBuilder(): RoomDatabase.Builder<ZaomengDatabase> =
        getDatabaseBuilder(appContext)

    override fun onDeviceRuntime(): OnDeviceRuntime = onDeviceRuntime

    /**
     * 运行日志采集器。
     *
     * 采集本身在 Application.onCreate 里由 [AndroidRuntimeLog.install] 启动（要尽早，
     * 否则漏掉启动阶段日志）；这里只把它作为日志来源暴露给诊断导出与「运行日志」页。
     * 即便 install 还没跑到，[AndroidRuntimeLog.snapshot] 也会走一次性 logcat 兜底。
     */
    override fun runtimeLogSource(): RuntimeLogSource = AndroidRuntimeLog

    override fun processUptimeMillis(): Long =
        (System.currentTimeMillis() - startedAtMillis).coerceAtLeast(0L)
}

internal fun File.toOkioPath(): Path = absolutePath.toPath()
