plugins {
    alias(libs.plugins.android.test)
}

// Checks of the release build with R8: smoke checks and the scroll of the generated library (docs/library.md §2.2).
android {
    namespace = "io.github.lorddetson.piplayer.macrobenchmark"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The API 30 emulator is one of the devices the limits are set for (docs/library.md §2.2).
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Only the benchmark build type measures the app as it ships.
androidComponents {
    beforeVariants(selector().all()) { variant -> variant.enable = variant.buildType == "benchmark" }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.assertj.core)
}
