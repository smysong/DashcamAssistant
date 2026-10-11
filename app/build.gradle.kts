import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing is optional and only uses a locally supplied, untracked keystore.
val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { load(it) }
    }
}
val keystorePath: String? = localProperties.getProperty("DASHCAM_KEYSTORE")
val keystorePassword: String? = localProperties.getProperty("DASHCAM_STORE_PASSWORD")
val keyAlias: String? = localProperties.getProperty("DASHCAM_KEY_ALIAS")
val keyPassword: String? = localProperties.getProperty("DASHCAM_KEY_PASSWORD")

val signingValues = listOf(keystorePath, keystorePassword, keyAlias, keyPassword)
val signingConfigured = signingValues.any { !it.isNullOrBlank() }
val hasSigning = signingValues.all { !it.isNullOrBlank() }
check(
    !signingConfigured || (hasSigning && rootProject.file(keystorePath!!).isFile)
) { "Release signing configuration is incomplete or the configured keystore is unavailable." }

android {
    namespace = "com.dashcam.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dashcam.assistant"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    if (hasSigning) {
        // 捕获外层 val，避免 Kotlin DSL 作用域内 keyAlias/keyPassword 被 SigningConfig 自身属性遮蔽
        val alias = keyAlias
        val password = keyPassword
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = alias
                keyPassword = password
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")

    // Android Material 库：提供 View 层 Theme.Material3.DayNight.NoActionBar 等主题资源
    // （Compose 的 androidx.compose.material3 只提供可组合项，不提供 View 主题）
    implementation("com.google.android.material:material:1.12.0")

    // Compose BOM（Kotlin 2.0.21 对应 2024.12.01）
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    testImplementation("junit:junit:4.13.2")

    // 协程（桥接/网络异步）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // 二维码生成（车机屏展示连接地址）
    implementation("com.google.zxing:core:3.5.3")

    // The car display uses Media3 RTSP/TCP for live view. The phone page has no live-streaming flow.
}
