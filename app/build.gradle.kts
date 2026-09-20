plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val withAmneNative = providers.gradleProperty("withAmneNative")
    .map(String::toBoolean)
    .orElse(false)
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
        if (withAmneNative.get()) {
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    if (withAmneNative.get()) {
        // AMNE is the only production native inference runtime in the AMPER single-core architecture.
        sourceSets.getByName("main").java.srcDir("src/amneNative/java")
        ndkVersion = "27.2.12479018"
        externalNativeBuild {
            cmake {
                path = file("src/amneNative/cpp/CMakeLists.txt")
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

    testImplementation("junit:junit:4.13.2")
}
