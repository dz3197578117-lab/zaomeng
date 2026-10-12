import org.jetbrains.kotlin.gradle.dsl.JvmTarget


plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

// ---------------------------------------------------------------------------
// 生成 /api/web/health 上报的版本号
// ---------------------------------------------------------------------------
// 这个字段此前写死 "0.1.0"，与应用版本毫无关系，排查时会误导。
// 版本号唯一来源是 kmp/gradle.properties，与 :ui:shared 的 AppMetadata 同源。
//
// 这里没有复用 :ui:shared 的生成结果，是因为本模块**不依赖** :ui:shared，
// 为了一个字符串去引入 UI 层依赖不划算；而 :core:contracts 也不在本模块
// 依赖里，为本就只是"报个版本号"的需求新增依赖同样不值得。
// 两处都只读同一个 gradle 属性，不会产生漂移。
val httpVersionDir = layout.buildDirectory.dir("generated/appVersion")

val generateHttpAppVersion by tasks.registering {
    val outputDir = httpVersionDir
    val versionName = providers.gradleProperty("zaomeng.versionName")
    inputs.property("versionName", versionName)
    outputs.dir(outputDir)
    doLast {
        val packageDir = outputDir.get().asFile.resolve("top/wkbin/zaomeng/ktor")
        packageDir.mkdirs()
        packageDir.resolve("GeneratedAppVersion.kt").writeText(
            """
            |// 由 :server:http 的 generateHttpAppVersion 任务生成，请勿手改。
            |// 来源：kmp/gradle.properties 的 zaomeng.versionName。
            |package top.wkbin.zaomeng.ktor
            |
            |internal object GeneratedAppVersion {
            |    const val VERSION_NAME = "${versionName.get()}"
            |}
            |
            """.trimMargin(),
        )
    }
}

kotlin {
    android {
        namespace = "top.wkbin.zaomeng.server.http"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
    jvm()


    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:runtime"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.okio)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.auth)
            implementation(libs.ktor.server.status.pages)
            implementation(libs.ktor.server.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// 挂上生成的源码目录；srcDir 接收 TaskProvider，会自动补上生成任务的依赖。
kotlin.sourceSets.named("commonMain") { kotlin.srcDir(generateHttpAppVersion) }
