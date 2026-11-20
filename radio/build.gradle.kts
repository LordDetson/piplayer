plugins {
    alias(libs.plugins.android.dynamic.feature)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.lorddetson.piplayer.radio"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }

    // Dynamic features must expose the same build types as the base.
    buildTypes {
        create("forum") { matchingFallbacks += "release" }
        create("benchmark") { matchingFallbacks += "release" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.all { test -> test.useJUnitPlatform() }
    }

    lint {
        error += "MissingTranslation"
        abortOnError = true
    }
}

dependencies {
    implementation(project(":app"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.media3.session)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
}
