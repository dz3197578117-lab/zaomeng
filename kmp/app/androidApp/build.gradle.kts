// Android 入口壳：只负责启动共享 UI（:app:shared），符合 Compose Multiplatform 模板规范。
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

val signingPropertiesFile = rootProject.file("keystore.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.exists()) {
        signingPropertiesFile.inputStream().use { input -> load(input) }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
        versionCode = 17
        versionName = "2.1.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (signingPropertiesFile.exists()) {
            // 项目专用签名密钥（keystore.properties 指定）。
            //
            // 刻意不用 Android SDK 自动生成的 ~/.android/debug.keystore：
            // 那把密钥由每台机器随机生成，一旦丢失或换机器，新包签名就与旧包不一致，
            // 用户无法覆盖安装（只能卸载重装），CI 里也无法复现。
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
            // 端侧推理需要执行 jniLibs/arm64-v8a/libllama-server.so（实际是 llama-server 可执行文件）。
            // 必须让 AGP 把原生库解压到 /data/app/<pkg>/lib/arm64/，那里才允许 execve；
            // 若沿用默认的 useLegacyPackaging=false，原生库只留在 APK 内，无法作为进程启动。
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
            // debug 包（applicationId 带 .test 后缀）同样用专用密钥签名。
            // 它在用户设备上是独立应用身份，更新链路一样要求签名稳定：
            // 沿用机器自带的 debug 密钥会让每次发版都可能装不上。
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

dependencies {
    implementation(project(":app:shared"))
    implementation(project(":data:remote"))
    // Compose resources from transitive KMP libraries are merged into the
    // consumer's resource namespace by AGP. Keep the owning module as a direct
    // dependency so its generated resource path remains available at runtime.
    implementation(project(":ui:shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
