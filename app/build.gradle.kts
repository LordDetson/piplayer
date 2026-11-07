import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
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

// The user database's schema, one file per version, committed; the migration tests read it (docs/library.md §3.1).
room3 {
    schemaDirectory("$projectDir/schemas")
}

// The native components of THIRD_PARTY_NOTICES.md, with the licence files :nativelib puts into the assets; the open-source
// licences screen lists them after the Maven modules (docs/library.md §14.2).
val nativeLibraries = listOf(
    NativeLibrary("TagLib", "2.3.2", "GNU LGPL 2.1", listOf("licenses/taglib-COPYING.LGPL")),
    NativeLibrary("utfcpp", "pinned by TagLib 2.3.2", "Boost Software License 1.0", listOf("licenses/utfcpp-LICENSE")),
    NativeLibrary("Chromaprint", "1.6.2", "MIT License", listOf("licenses/chromaprint-LICENSE.md")),
    NativeLibrary(
        "KissFFT", "bundled with Chromaprint 1.6.2", "BSD 3-Clause License",
        listOf("licenses/kissfft-COPYING", "licenses/kissfft-BSD-3-Clause"),
    ),
    NativeLibrary(
        "speexdsp (resampler)", "1.2.1", "BSD 3-Clause License",
        listOf("licenses/speexdsp-resampler-NOTICE.txt", "licenses/speexdsp-COPYING"),
    ),
    NativeLibrary(
        "LLVM libc++ and libc++abi", "NDK 30.0.16248370", "Apache License 2.0 with LLVM Exceptions",
        listOf("licenses/libc++-LICENSE.txt"),
    ),
)

// The copyright notices of the runtime modules whose licence text asks for "the above copyright notice", taken from the
// module's own licence file; a new such module without one fails the licence list (docs/library.md §14.2).
val licenseNotices = mapOf(
    "org.checkerframework:checker-qual" to "Copyright 2004-present by the Checker Framework developers",
    // AndroidX's copy of Protocol Buffers; the licence file in its jar leaves the line out, protobuf's own has it.
    "androidx.datastore:datastore-preferences-external-protobuf" to "Copyright 2008 Google Inc. All rights reserved.",
)

androidComponents {
    onVariants { variant ->
        variant.androidTest?.sources?.assets
            ?.addGeneratedSourceDirectory(prepareRecordings, PrepareFingerprintSamples::outputDir)
        val (licenses, components) = registerDependencyLicenses(
            variant.name, variant.runtimeConfiguration, layout.projectDirectory.dir("licenses"), licenseNotices, nativeLibraries,
        )
        variant.sources.assets?.addGeneratedSourceDirectory(licenses, CollectDependencyLicenses::outputDir)
        // The JVM check of the list (docs/library.md §14.4) compares it with the variant's runtime modules.
        val unitTest = "test${variant.name.replaceFirstChar { it.uppercase() }}UnitTest"
        tasks.withType<Test>().configureEach {
            if (name != unitTest) return@configureEach
            val list = licenses.flatMap { it.outputDir }
            val expected = components.flatMap { it.output }
            inputs.dir(list).withPropertyName("licenseList").withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.file(expected).withPropertyName("runtimeComponents").withPathSensitivity(PathSensitivity.NONE)
            jvmArgumentProviders += CommandLineArgumentProvider {
                listOf(
                    "-Dpiplayer.licenses.dir=${list.get().asFile.path}",
                    "-Dpiplayer.licenses.components=${expected.get().asFile.path}",
                )
            }
        }
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
        // Turns the crash dialog off (docs/playback.md §7.4).
        testInstrumentationRunner = "io.github.lorddetson.piplayer.PiPlayerTestRunner"
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
            // R8 and resource shrinking (docs/library.md §2.1).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // The release build signed with the debug key and profileable, for the Macrobenchmark checks (docs/library.md
        // §2.1); never published.
        create("benchmark") {
            initWith(getByName("release"))
            // A package of its own: a connected run uninstalls the app it tested, which must not be the debug build.
            applicationIdSuffix = ".benchmark"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    sourceSets {
        // The benchmark build is the release build, with the release versions of the debug-only classes.
        getByName("benchmark") { kotlin.directories.add("src/release/kotlin") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // The tag reader process's interface (docs/library.md §4.1).
        aidl = true
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
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.reorderable)
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
    implementation(libs.room3.runtime)
    // The SQLite bundled with the app: one version on every device, with JSON for bulk writes (docs/library.md §1.1).
    implementation(libs.androidx.sqlite.bundled)
    ksp(libs.room3.compiler)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
    // The platform's ICU on the JVM, for the sort keys and letters (docs/library.md §11.4); tests only.
    testImplementation(libs.icu4j)
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
    // The M0 Room spike (library search) also measures the platform's SQLite.
    androidTestImplementation(libs.androidx.sqlite.framework)
    androidTestImplementation(libs.room3.testing)
    kspAndroidTest(libs.room3.compiler)
}
