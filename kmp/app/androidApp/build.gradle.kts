// ============================================================================
// 造梦 —— 单模块 Android 应用
// ============================================================================
// 取代原来 33 个模块的构建脚本。当前状态：
//
//   * **纯 Android 模块**：com.android.application + AGP 9 自带 Kotlin 编译，
//     不使用 org.jetbrains.kotlin.multiplatform（AGP 9 起 application 插件与 KMP 互斥）。
//   * 全部源码已物理搬迁到本模块：src/main/kotlin（379 个）与 src/test/kotlin（108 个）。
//     原 core/、data/、feature/、server/、ui/、plugins-api/、builtin-plugins/ 等 33 个模块
//     及其 build.gradle.kts 已删除。
//   * expect/actual 已消除（commonMain 的 expect 已删，androidMain 的实现去掉 actual）。
//   * Compose 仍用 Compose Multiplatform 制品（Android 上即 androidx 实现）；
//     换成纯 AndroidX 坐标是后续独立步骤。
// ============================================================================
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile



// ---------------------------------------------------------------------------
// 构建期生成版本号常量
// ---------------------------------------------------------------------------
// 版本号唯一来源是 gradle.properties 的 zaomeng.versionName / versionCode。
// 合并前这两份常量分别由 :ui:shared 与 :server:http 各自生成（当时是为了避免
// 为一个字符串引入跨模块依赖）；现在只有一个模块，这个理由不再成立，于是由
// 同一个任务一次生成两份 —— **包名与类名保持原样**，因此所有调用方零改动，
// 且 scripts/check-release-ready.sh 的「不得手写版本号」守卫依旧成立。
abstract class GenerateAppVersionTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val versionCode: Property<String>

    @TaskAction
    fun generate() {
        val root = outputDir.get().asFile
        val appSharedDir = root.resolve("top/wkbin/zaomeng/app/shared").apply { mkdirs() }
        appSharedDir.resolve("AppMetadata.kt").writeText(
            """
            |// 由 :app:androidApp 的 generateAppVersion 任务生成，请勿手改。
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

val generateAppVersion = tasks.register<GenerateAppVersionTask>("generateAppVersion") {
    versionName.set(providers.gradleProperty("zaomeng.versionName"))
    versionCode.set(providers.gradleProperty("zaomeng.versionCode"))
}



val signingPropertiesFile = rootProject.file("keystore.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.exists()) {
        signingPropertiesFile.inputStream().use { input -> load(input) }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room3)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

android {
    namespace = "top.wkbin.zaomeng.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "top.wkbin.zaomeng"
        minSdk = libs.versions.android.minSdk.get().toInt()
        //noinspection OldTargetApi
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = providers.gradleProperty("zaomeng.versionCode").get().toInt()
        versionName = providers.gradleProperty("zaomeng.versionName").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // 只分发 arm64-v8a。端侧推理（libllama-server.so）本来就只编了这一档，
            // 而神经 TTS 的 AAR 里还带 armeabi-v7a / x86 / x86_64 共 91 MB 原生库——
            // 不加过滤，那些包会原样进 APK，用户白下 91 MB 却永远用不到。
            abiFilters += "arm64-v8a"
        }
    }

    sourceSets["main"].apply {
        manifest.srcFile("src/main/AndroidManifest.xml")
        res.srcDirs("src/main/res")
        assets.srcDirs("src/main/assets")
        jniLibs.srcDirs("src/main/jniLibs")
        kotlin.srcDirs("src/main/kotlin")
    }
    sourceSets["debug"].apply {
        res.srcDirs("src/debug/res")
    }
    sourceSets["test"].apply {
        kotlin.srcDirs("src/test/kotlin")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // 单元测试跑在 JVM 上，android.jar 里的方法是桩（调用即抛 "not mocked"）。
            // 生产代码会经 PlatformLog 调用 android.util.Log，因此必须让桩返回默认值，
            // 否则纯逻辑测试会被日志调用带崩。
            isReturnDefaultValues = true
        }
    }

    signingConfigs {
        if (signingPropertiesFile.exists()) {
            create("zaomeng") {
                val storePath = signingProperties.getProperty("storeFile").orEmpty()
                storeFile = rootProject.file(storePath)
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/INDEX.LIST",
            )
        }
        jniLibs {
            // 端侧推理要执行 jniLibs/arm64-v8a/libllama-server.so（实为可执行文件），
            // 必须解压到 /data/app/<pkg>/lib/arm64/ 才允许 execve。
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            if (signingPropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("zaomeng")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".test"
            isMinifyEnabled = false
            if (signingPropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("zaomeng")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

room3 {
    // Room schema 仍指回原 server/storage 的 schema 目录（相对路径同样要按 Gradle 根解析）。
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    debugImplementation(libs.compose.ui.tooling)
    // 纯 Android 模块里 KSP 的配置名是 ksp（不是 KMP 的 kspAndroid）。
    add("ksp", libs.androidx.room3.compiler)

    // ---- 原 33 个模块依赖的并集（project(...) 依赖已合并为同模块引用）----
    implementation(libs.bundles.compose.ui)
    implementation(libs.compose.lifecycle.runtime)
    api(libs.compose.lifecycle.viewmodel)
    implementation(libs.bundles.koin.compose)
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.io.okio)
    implementation(libs.okio)
    implementation(libs.okhttp)
    implementation(libs.snakeyaml.engine.kmp)
    implementation(libs.paging.common)
    implementation(libs.paging.compose)
    implementation(libs.material.kolor)
    implementation(libs.bundles.filekit.compose)
    implementation(libs.filekit.core)
    implementation(libs.bundles.androidx.datastore)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.compose.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigationevent.compose)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.androidx.compose.bom))

    // 离线神经 TTS 引擎（sherpa-onnx）。原生库随 AAR 分发，ABI 由上面的 ndk.abiFilters
    // 收敛到 arm64-v8a。这里用**任务名**（字符串）而不是 Kotlin 变量引用：下载任务的声明
    // 在本文件末尾，而 dependencies 块在脚本执行时就要求值，直接引用变量会拿到未初始化的值。
    // builtBy 用任务名是惰性解析的，没有这个顺序问题。
    implementation(
        files(
            rootProject.layout.projectDirectory.dir(".gradle/sherpa-onnx")
                .file("sherpa-onnx-${libs.versions.sherpaOnnx.get()}.aar"),
        ).builtBy("downloadSherpaOnnx"),
    )

    // 内嵌 Ktor 服务端 + 客户端（localhost HTTP/SSE 链路，后续步骤整体去掉）
    // 只保留 Ktor **客户端**：对外 HTTP（模型 API / 在线书库 / 更新检查 / llama-server）。
    // 内嵌的本地 HTTP 服务端已整体移除，客户端改为进程内直连 AppServices。
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.serialization.kotlinx.json)

    // 持久化
    api(libs.androidx.room3.runtime)
    api(libs.androidx.sqlite.bundled)

    // ---- 单元测试 ----
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    // 显式声明：探针上下文的结构性测试要枚举类成员（FeatureProbeContext::class.members）。
    // 以前它是被某个 Ktor 服务端构件**传递**带进来的——服务端依赖一移除就静默炸掉，
    // 说明那种隐式依赖本来就不可靠。
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.snakeyaml)
    testImplementation(libs.okhttp)
    // Room 的 bundled SQLite 驱动在 Android 变体里带的是 bionic 原生库（.so for Android），
    // 单元测试跑在 JVM 上无法加载它；必须显式引入 JVM 变体（glibc 原生库）才能建库。
    testImplementation(libs.androidx.sqlite.bundled.jvm)
}

// AGP 9 起不允许把 Task Provider 直接加进 Android SourceSet API
// （它无法判断该目录是生成物还是静态文件），必须走 Variant API 声明「生成目录」，
// 这样任务依赖也会被自动带上。
androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(generateAppVersion) { it.outputDir }
    }
}

// ---------------------------------------------------------------------------
// 单元测试用的 SQLite 原生库
// ---------------------------------------------------------------------------
// `androidx.sqlite:sqlite-bundled` 的 JVM 变体把原生库放在 jar 内的
// `natives/<os>_<arch>/libsqliteJni.so`，运行时通过 System.loadLibrary("sqliteJni") 加载，
// 因此必须让它出现在 java.library.path 上——jar 里的资源不会自动生效。
// 不处理就会在 Room 相关用例上得到：
//   UnsatisfiedLinkError: no sqliteJni in java.library.path
// 注意本机是 aarch64 容器，os.name 可能被识别为 Linux-Android，所以这里按 os.arch 自行判定，
// 不依赖驱动的平台探测。
val sqliteNativesDir = layout.buildDirectory.dir("sqlite-natives")

tasks.withType<Test>().configureEach {
    // 从单元测试自身的 classpath 里取 sqlite-bundled-jvm，把里面的原生库解出来。
    // 不用 configuration 名字（Android 模块没有 testRuntimeClasspath，只有
    // debugUnitTestRuntimeClasspath 这类变体名），直接从 Test 任务的 classpath 取最稳。
    val nativesDir = sqliteNativesDir.get().asFile
    doFirst {
        nativesDir.mkdirs()
        val arch = when (System.getProperty("os.arch").lowercase()) {
            "aarch64", "arm64" -> "linux_arm64"
            "x86_64", "amd64" -> "linux_x64"
            else -> null
        }
        val jar = classpath.files.firstOrNull { it.name.startsWith("sqlite-bundled-jvm") }
        when {
            arch == null ->
                logger.lifecycle("跳过 SQLite 原生库提取：未适配的 os.arch=${System.getProperty("os.arch")}")
            jar == null ->
                logger.lifecycle("跳过 SQLite 原生库提取：classpath 上没有 sqlite-bundled-jvm")
            else -> {
                var extracted = 0
                ZipFile(jar).use { zip ->
                    zip.entries().asSequence()
                        .filter { it.name.startsWith("natives/$arch/") && it.name.endsWith(".so") }
                        .forEach { entry ->
                            val target = nativesDir.resolve(entry.name.substringAfterLast('/'))
                            zip.getInputStream(entry).use { input ->
                                target.outputStream().use { output -> input.copyTo(output) }
                            }
                            extracted++
                        }
                }
                logger.lifecycle("SQLite 原生库：提取 $extracted 个（$arch）→ $nativesDir")
            }
        }
    }
    // 追加而不是覆盖：保留 JVM 默认的库搜索路径。
    val existing = System.getProperty("java.library.path").orEmpty()
    val nativesPath = nativesDir.absolutePath
    systemProperty("java.library.path", if (existing.isEmpty()) nativesPath else "$nativesPath:$existing")
}

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
// 缓存位置选 `kmp/.gradle/`：它已被 kmp/.gitignore 覆盖，且不会被 `clean` 清掉，
// 因此换分支或 clean 之后不必重新下载 48 MB。
//
// ⚠️ 若所在网络访问不了 github.com，可以用资产 API 端点绕过：
//   `api.github.com/repos/k2-fsa/sherpa-onnx/releases/assets/<id>` 加请求头
//   `Accept: application/octet-stream`，它会 302 到可访问的 release-assets 域。
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
