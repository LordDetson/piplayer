import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * The AcoustID application key, from -Pacoustid.apiKey or acoustid.apiKey in local.properties (never in git); empty
 * when neither is set, and then the spike skips the lookup.
 */
val acoustIdApiKey: String = providers.gradleProperty("acoustid.apiKey")
    .orElse(
        providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText.map { text ->
            Properties().apply { load(text.reader()) }.getProperty("acoustid.apiKey", "")
        },
    )
    .orElse("")
    .get()
    .trim()
// Checked so that the value can go into generated Java source as it is; the message never repeats the key.
require(acoustIdApiKey.matches(Regex("[A-Za-z0-9]*"))) { "acoustid.apiKey may contain only letters and digits" }

val prepareRecordings = tasks.register<PrepareFingerprintSamples>("prepareRecordings") {
    manifest = rootProject.layout.projectDirectory.file("nativelib/fingerprint-samples.json")
    script = rootProject.layout.projectDirectory.file("scripts/prepare_fingerprint_samples.py")
    mode = "files"
    fpcalc = fpcalcBinary()
    workingDirectory = rootProject.layout.projectDirectory
    python = providers.gradleProperty("piplayer.python")
        .orElse(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3")
}

androidComponents {
    onVariants { variant ->
        variant.androidTest?.sources?.assets
            ?.addGeneratedSourceDirectory(prepareRecordings, PrepareFingerprintSamples::outputDir)
    }
}

android {
    namespace = "io.github.lorddetson.piplayer"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.lorddetson.piplayer"
        minSdk = 30
        // Explicit: AGP 9 would otherwise default targetSdk to compileSdk.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // The native code (TagLib, Chromaprint) is built for these only; without the filter the APK would also
            // claim 32-bit ABIs through other libraries' .so files and crash there.
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        debug {
            // Only the debug identification spike looks fingerprints up in M0.
            buildConfigField("String", "ACOUSTID_API_KEY", "\"$acoustIdApiKey\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    testOptions {
        unitTests.all { test ->
            test.useJUnitPlatform()
            // The M0 corpus run sends a private list to MusicBrainz, so it runs only when these are given.
            listOf(
                "corpus.file", "corpus.out", "corpus.sample", "corpus.bracketSuffix", "corpus.requireTitleWords",
            ).forEach { name ->
                providers.gradleProperty(name).orNull?.let { test.systemProperty(name, it) }
            }
        }
    }

    lint {
        // Every user-facing string must exist in every shipped language.
        error += "MissingTranslation"
        abortOnError = true
    }
}

dependencies {
    implementation(project(":nativelib"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.profileinstaller)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.media3.inspector)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.okhttp.mockwebserver3.junit5)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.assertj.core)
    // The M0 Room spike (library search) lives in the instrumented tests only; the library schema comes with M2a.
    androidTestImplementation(libs.room3.runtime)
    androidTestImplementation(libs.androidx.sqlite.framework)
    androidTestImplementation(libs.androidx.sqlite.bundled)
    kspAndroidTest(libs.room3.compiler)
}
