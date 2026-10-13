// 内置插件接口模块（不依赖任何 Android 实现，server 与 builtin-plugins 都依赖它）。
// KMP：commonMain 共享插件契约，androidLibrary 供 App 使用，jvm() 供桌面/测试。
import org.jetbrains.kotlin.gradle.dsl.JvmTarget


plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        namespace = "top.wkbin.zaomeng.plugins.api"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
    jvm()

    // Apple target 只能在 macOS 主机上编译；非 macOS 主机（Linux / CI）自动跳过，保持本机构建可跑。

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
