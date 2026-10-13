import java.net.URI
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget


plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

compose.resources {
    publicResClass = true
}

// ---------------------------------------------------------------------------
// 生成 AppMetadata —— 版本号唯一来源是 kmp/gradle.properties
// ---------------------------------------------------------------------------
// 此前 AppMetadata 是 commonMain 里手写的常量，发版时没人记得改：
// 界面显示 2.1.2 而 APK 已是 2.1.4，桌面端更新检查也一直误判自己落后。
// 改为构建期生成，与 androidApp / desktopApp 的版本号同源，无法再漂移。
//
// 只生成版本号，**不生成构建时间**：构建时间每次都会变，会让这个任务永远不是
// UP-TO-DATE，进而导致 ui/shared 及其全部下游模块每次构建都重新编译。
val appMetadataDir = layout.buildDirectory.dir("generated/appMetadata")

val generateAppMetadata by tasks.registering {
    val outputDir = appMetadataDir
    val versionName = providers.gradleProperty("zaomeng.versionName")
    val versionCode = providers.gradleProperty("zaomeng.versionCode")
    inputs.property("versionName", versionName)
    inputs.property("versionCode", versionCode)
    outputs.dir(outputDir)
    doLast {
        val packageDir = outputDir.get().asFile.resolve("top/wkbin/zaomeng/app/shared")
        packageDir.mkdirs()
        packageDir.resolve("AppMetadata.kt").writeText(
            """
            |// 由 :ui:shared 的 generateAppMetadata 任务生成，请勿手改。
            |// 版本号来源：kmp/gradle.properties 的 zaomeng.versionName / zaomeng.versionCode。
            |package top.wkbin.zaomeng.app.shared
            |
            |object AppMetadata {
            |    const val VERSION_NAME = "${versionName.get()}"
            |    const val VERSION_CODE = ${versionCode.get()}
            |}
            |
            """.trimMargin(),
        )
    }
}

kotlin {
    jvm()


    android {
        namespace = "top.wkbin.zaomeng.ui.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        androidResources {
            enable = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":data:remote"))
            // R7：布局跳动探针要把采样写进客户端诊断收口。
            implementation(project(":core:runtime"))
            implementation(libs.bundles.compose.ui)
            implementation(libs.material.kolor)
            implementation(libs.bundles.filekit.compose)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.okio)
            implementation(libs.okio)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            // 离线神经 TTS 引擎。原生库随 AAR 分发，ABI 由 androidApp 的 abiFilters 收敛到 arm64-v8a
            // （AAR 里另外三个 ABI 共 91 MB，不加过滤就会白白进包）。
            //
            // 这里用**任务名**（字符串）而不是 Kotlin 变量引用：下载任务的声明在本文件末尾，
            // 而这个 dependencies 块在脚本执行时就要求值，直接引用变量会拿到未初始化的值。
            // builtBy 用任务名是惰性解析的，没有这个顺序问题。
            implementation(
                files(
                    rootProject.layout.projectDirectory.dir(".gradle/sherpa-onnx")
                        .file("sherpa-onnx-${libs.versions.sherpaOnnx.get()}.aar"),
                ).builtBy("downloadSherpaOnnx"),
            )
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// 把生成的源码目录挂到 commonMain。srcDir 接收 TaskProvider，
// Gradle 会自动补上「先跑生成任务」的依赖。
kotlin.sourceSets.named("commonMain") { kotlin.srcDir(generateAppMetadata) }

// ---------------------------------------------------------------------------
// 离线神经 TTS 引擎（sherpa-onnx）：构建期下载 AAR，绝不把 50 MB 二进制提交进仓库
// ---------------------------------------------------------------------------
// 为什么不用 Maven 坐标：
//   ① Maven Central 上查无此物；
//   ② JitPack 上只有 `com.github.k2-fsa:sherpa-onnx` 的 **jar**（不含原生库），
//      Android 可用的 `sherpa-onnx-<版本>.aar` 只发布在 GitHub Release 的资产里。
// 所以只能按 URL 取，并**校验 sha256**——原生库一旦被换掉或下坏，症状是运行时静默不响，
// 很难查；宁可在这里直接失败。
//
// 缓存位置选 `kmp/.gradle/`：它已被 .gitignore 覆盖，且不会被 `clean` 清掉，
// 因此换分支或 clean 之后不必重新下载 48 MB。
//
// ⚠️ 若所在网络访问不了 github.com（本仓库开发过程中遇到过），
// 可以用资产 API 端点绕过：`api.github.com/repos/k2-fsa/sherpa-onnx/releases/assets/<id>`
// 加请求头 `Accept: application/octet-stream`，它会 302 到可访问的 release-assets 域。
// 下好后放到下面的缓存路径即可，任务会校验摘要并跳过下载。
val sherpaOnnxVersion = libs.versions.sherpaOnnx.get()
val sherpaOnnxAarSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
val sherpaOnnxAarFile: File = rootProject.layout.projectDirectory
    .dir(".gradle/sherpa-onnx")
    .file("sherpa-onnx-$sherpaOnnxVersion.aar")
    .asFile

// 注意：这里必须用顶部 import 的 `MessageDigest`，不能写 `java.security.MessageDigest`——
// 在 Android 的 Kotlin DSL 脚本里 `java` 会被 Gradle 的 java 扩展遮蔽，
// 写全限定名会得到 `Unresolved reference 'security'`（踩过一次）。
fun sha256Of(file: File): String =
    MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

val downloadSherpaOnnx: TaskProvider<Task> = tasks.register("downloadSherpaOnnx") {
    description = "下载并校验 sherpa-onnx 的 Android AAR（离线神经 TTS 引擎）"
    outputs.file(sherpaOnnxAarFile)
    outputs.upToDateWhen {
        sherpaOnnxAarFile.isFile && sha256Of(sherpaOnnxAarFile) == sherpaOnnxAarSha256
    }
    doLast {
        if (sherpaOnnxAarFile.isFile && sha256Of(sherpaOnnxAarFile) == sherpaOnnxAarSha256) {
            logger.lifecycle("[sherpa-onnx] 缓存已存在且校验通过：${sherpaOnnxAarFile.absolutePath}")
            return@doLast
        }
        sherpaOnnxAarFile.parentFile.mkdirs()
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
            "v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar"
        val temp = File(sherpaOnnxAarFile.parentFile, "${sherpaOnnxAarFile.name}.part")
        var lastError: Exception? = null
        for (attempt in 1..5) {
            try {
                logger.lifecycle("[sherpa-onnx] 开始下载（第 $attempt 次）：$url")
                temp.delete()
                URI(url).toURL().openStream().use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                val digest = sha256Of(temp)
                if (digest != sherpaOnnxAarSha256) {
                    error("sherpa-onnx AAR 校验失败：期望 $sherpaOnnxAarSha256，实际 $digest")
                }
                temp.renameTo(sherpaOnnxAarFile)
                logger.lifecycle("[sherpa-onnx] 下载完成：${sherpaOnnxAarFile.length()} B")
                return@doLast
            } catch (exception: Exception) {
                lastError = exception
                temp.delete()
                logger.warn("[sherpa-onnx] 第 $attempt 次失败：${exception.message}")
                Thread.sleep(3_000)
            }
        }
        throw GradleException(
            "下载 sherpa-onnx AAR 失败：${lastError?.message}。" +
                "若 github.com 不可达，可按本任务上方注释用资产 API 端点手动下载到 " +
                "${sherpaOnnxAarFile.absolutePath}",
            lastError,
        )
    }
}
