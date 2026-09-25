import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// ---------------------------------------------------------------------------
// 正式签名配置
//
// 为什么要用它：用 Android 调试密钥签出来的包**不具备可证明来源** ——
// 调试密钥是公开的、密码固定为 "android"，任何人都能签出同名的伪造包。
// 换成自己的正式密钥后，接收方可以核对证书指纹，确认这个 APK 确实出自同一个作者。
//
// 密钥信息放在根目录的 keystore.properties 里（该文件不应提交到代码仓库）。
// 文件不存在时自动退回调试签名，保证构建永远不会因此失败。
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile")?.isNotBlank() == true

android {
    namespace = "com.aotu.fire"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.aotu.fire"
        // 30 = Android 11：AccessibilityService.takeScreenshot 的门槛
        minSdk = 30
        // 36 = Android 16，与手机系统一致
        targetSdk = 36
        versionCode = 1
        versionName = "2.6"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // 同时启用 v1 与 v2/v3 签名：老设备与新设备都能校验
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            // 关闭混淆：保持代码可读，便于接收方自行反编译核对行为
            optimization {
                enable = false
            }
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// 刻意保持零第三方依赖：只用 Android SDK 原生态 API。
// 好处：构建不依赖任何外部仓库、APK 更小、不会与 targetSdk 36 产生版本冲突。
dependencies {
}
