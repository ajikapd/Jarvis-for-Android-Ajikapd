
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.jarvis.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.pro"
        minSdk = 29
        targetSdk = 34
        versionCode = 19
        versionName = "1.4.1-pro"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    androidResources {
        noCompress += listOf("onnx", "tflite", "bin")
    }

    packaging {
        jniLibs {
            pickFirsts += setOf(
                "**/libonnxruntime.so",
                "**/libc++_shared.so",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":background-service"))
    implementation(project(":permissions-onboarding"))
    implementation(project(":brain"))
    implementation(project(":tools"))
    implementation(project(":screen-control"))
    implementation(project(":audio-engine"))
    implementation(project(":asr-engine"))
    implementation(project(":wakeword-engine"))
    implementation(project(":tts-engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.commons.compress)
    implementation(libs.llama.android)
}
