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
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// 把生成的源码目录挂到 commonMain。srcDir 接收 TaskProvider，
// Gradle 会自动补上「先跑生成任务」的依赖。
kotlin.sourceSets.named("commonMain") { kotlin.srcDir(generateAppMetadata) }
