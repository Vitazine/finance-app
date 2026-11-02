import com.android.build.gradle.LibraryExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

val coreOnly = providers.gradleProperty("coreOnly").map(String::toBoolean).getOrElse(false)
if (!coreOnly) {
    apply(plugin = "com.android.library")
    extensions.configure<LibraryExtension> {
        namespace = "app.finance.shared"
        compileSdk = 36
        defaultConfig.minSdk = 29
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")
    jvm()
    if (!coreOnly) {
        androidTarget()
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.bignum)
            api(libs.kotlinx.datetime)
            implementation(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(libs.room.testing)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

ktlint {
    version.set(libs.versions.ktlint)
    filter {
        exclude {
            it.file.toPath().startsWith(
                layout.buildDirectory.dir("generated/ksp").get().asFile.toPath()
            )
        }
    }
}

dependencies {
    add("kspJvm", libs.room.compiler)
    if (!coreOnly) add("kspAndroid", libs.room.compiler)
}

room {
    schemaDirectory("$projectDir/schemas")
}
