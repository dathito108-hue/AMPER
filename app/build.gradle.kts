plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val withLlamaAar = providers.gradleProperty("withLlamaAar")
    .map(String::toBoolean)
    .orElse(false)
val llamaAndroidVersion = "0.1.1"
val withMtmdNative = providers.gradleProperty("withMtmdNative")
    .map(String::toBoolean)
    .orElse(false)
val withMtmdVulkan = providers.gradleProperty("withMtmdVulkan")
    .map(String::toBoolean)
    .orElse(false)
val spirvHeadersDir = providers.gradleProperty("spirvHeadersDir").orNull
val vulkanHeadersDir = providers.gradleProperty("vulkanHeadersDir").orNull
val withMtmdNativeRuntime = withMtmdNative.get() || withMtmdVulkan.get()

require(!(withMtmdNative.get() && withMtmdVulkan.get())) {
    "withMtmdNative and withMtmdVulkan are distinct CPU/Vulkan native runtime modes; enable only one"
}
require(!(withLlamaAar.get() && withMtmdNativeRuntime)) {
    "withLlamaAar and native MTMD runtimes package different llama.cpp runtimes; enable only one"
}

android {
    namespace = "io.amper.neuroos"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.amper.neuroos"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-canonical"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "LLAMA_ANDROID_VERSION", "\"$llamaAndroidVersion\"")
        if (withMtmdNativeRuntime) {
            ndk {
                abiFilters += "arm64-v8a"
            }
            externalNativeBuild {
                cmake {
                    arguments += "-DAMPER_MTMD_VULKAN=" +
                        if (withMtmdVulkan.get()) "ON" else "OFF"
                    if (withMtmdVulkan.get() && !spirvHeadersDir.isNullOrBlank()) {
                        arguments += "-DAMPER_SPIRV_HEADERS_DIR=$spirvHeadersDir"
                    }
                    if (withMtmdVulkan.get() && !vulkanHeadersDir.isNullOrBlank()) {
                        arguments += "-DAMPER_VULKAN_HEADERS_DIR=$vulkanHeadersDir"
                    }
                }
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    if (withLlamaAar.get()) {
        // Optional backend sources are compiled only when the prebuilt runtime is requested.
        sourceSets.getByName("main").java.srcDir("src/llamaAar/java")
    }

    if (withMtmdNativeRuntime) {
        // Phase148+ native multimodal runtime. Phase156 optionally compiles Vulkan acceleration.
        sourceSets.getByName("main").java.srcDir("src/mtmdNative/java")
        ndkVersion = "27.2.12479018"
        externalNativeBuild {
            cmake {
                path = file("src/mtmdNative/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    if (withLlamaAar.get()) {
        // Prebuilt arm64-v8a llama.cpp runtime. No NDK/CMake is added to :app.
        implementation("dev.ffmpegkit-maintained:llama-android:$llamaAndroidVersion")
    }

    testImplementation("junit:junit:4.13.2")
}
