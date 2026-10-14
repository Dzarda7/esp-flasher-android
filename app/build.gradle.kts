plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Bumped by commitizen (`cz bump`), see .cz.toml.
val appVersion = "0.1.0"

val flasherDir = rootProject.file(providers.gradleProperty("espSerialFlasherDir").get())
val flasherVersion = flasherDir.resolve("idf_component.yml").readLines()
    .firstOrNull { it.startsWith("version:") }?.substringAfter(":")?.trim()?.trim('"') ?: "unknown"

android {
    namespace = "io.github.dzarda7.espflasher"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.dzarda7.espflasher"
        minSdk = 26
        targetSdk = 37
        // Play Store needs an ever increasing integer: 1.2.3 -> 10203.
        versionCode = appVersion.split(".").map { it.toInt() }
            .let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }
            .coerceAtLeast(1)
        versionName = appVersion
        buildConfigField("String", "FLASHER_VERSION", "\"$flasherVersion\"")

        externalNativeBuild {
            cmake {
                arguments += "-DESP_SERIAL_FLASHER_DIR=${flasherDir.absolutePath}"
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    // Release signing comes from the environment (GitHub secrets in CI); without it the
    // release build is unsigned.
    val keystore = providers.environmentVariable("ANDROID_KEYSTORE").orNull
    if (keystore != null) {
        signingConfigs.create("release") {
            storeFile = file(keystore)
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
}
