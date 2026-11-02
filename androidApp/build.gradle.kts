plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

val nativeAi = providers.gradleProperty("nativeAi").map(String::toBoolean).getOrElse(false)

android {
    namespace = "app.finance.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.finance.android"
        buildConfigField("boolean", "NATIVE_AI", nativeAi.toString())
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (nativeAi) {
        ndkVersion = "27.2.12479018"
        defaultConfig {
            ndk.abiFilters.add("arm64-v8a")
            externalNativeBuild.cmake {
                arguments +=
                    "-DLLAMA_SOURCE_DIR=${providers.gradleProperty("llamaSourceDir").get()}"
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
        externalNativeBuild.cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures.compose = true
    buildFeatures.buildConfig = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        warningsAsErrors = true
        abortOnError = true
    }
}

kotlin {
    jvmToolchain(17)
}

ktlint {
    version.set(libs.versions.ktlint)
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.core)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.coroutines.core)
    androidTestImplementation(libs.sqlite.bundled)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
