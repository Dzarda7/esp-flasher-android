plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

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
        versionCode = 1
        versionName = "0.1.0"
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

    buildTypes {
        release {
            isMinifyEnabled = false
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
