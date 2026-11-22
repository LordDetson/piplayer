import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

/**
 * The AcoustID application key, from -Pacoustid.apiKey or acoustid.apiKey in local.properties (never in git), for every
 * build type (TI §9). Empty when neither is set: the build then has no fingerprint lookup and identifies tracks by the
 * other stages (known IDs, ISRC, text search). A shipped key is not a secret, but the build never prints it.
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

/**
 * The contact in the User-Agent (TI §9): -Ppiplayer.contactUrl or piplayer.contactUrl in gradle.properties, else this
 * repository. A fork sets its own URL or e-mail address (README, "Forks").
 */
val contactUrl: String = providers.gradleProperty("piplayer.contactUrl")
    .orElse("https://github.com/LordDetson/piplayer")
    .get()
    .trim()
// One token of printable ASCII without quotes, backslashes or parentheses: it goes into generated Java source as it is
// and into the comment of the User-Agent, which a parenthesis would end. The contact is public, so the message shows it.
require(contactUrl.matches(Regex("""[!#-'*-\[\]-~]+"""))) {
    "piplayer.contactUrl must be one token of printable ASCII without spaces, quotes, backslashes or parentheses: " +
        "'$contactUrl'"
}

/**
 * -Ppiplayer.minifiedTests=true runs the instrumented tests against the `benchmark` build, the release build with R8
 * signed with the debug key, instead of the debug build (TI §11.2, M3 step 7), so that MinifiedClientsTest checks the
 * identification clients under R8 without a self-test inside the app. AGP tests a minified build as it is: the test APK
 * is processed against the app's mapping, and app code that only tests call must be kept by rules of the run's own
 * ([minifiedTestRules] for the app, benchmark only; proguard-minified-test-apk.pro for the test APK). The test APK holds
 * only [minifiedTestSources]: the other instrumented tests use debug-only classes and test the debug build. A local run
 * on the emulator, never on CI; the install tasks serve every connected device, so ANDROID_SERIAL names the emulator:
 *
 * ```
 * ANDROID_SERIAL=<emulator> gradlew :app:installBenchmark :app:installBenchmarkAndroidTest -Ppiplayer.minifiedTests=true
 * python <main folder>/tools/instrumented/run_instrumented.py <emulator> MinifiedClientsTest --no-install -e minified true
 * ```
 *
 * `-e minified true` makes MinifiedClientsTest fail when the build under test is not the benchmark one, and the helper
 * refuses it without --no-install, which would reinstall the debug build. The test APK keeps the debug run's package, so
 * the next normal run must install again (the helper refuses --no-install while the device holds the benchmark test
 * APK). The keep rules name the entry points by hand: MinifiedTestRulesTest (a JVM test) fails when one is renamed or
 * removed or when TestServices or IdentificationServices.create takes a new app type, and the minified run is repeated
 * whenever IdentificationServices.create, TestServices or the clients' calls change.
 */
val minifiedTests: Boolean = providers.gradleProperty("piplayer.minifiedTests").map { it.toBoolean() }.getOrElse(false)
val minifiedTestRules = "proguard-minified-tests.pro"
val minifiedTestSources = listOf(
    "io/github/lorddetson/piplayer/PiPlayerTestRunner.kt",
    "io/github/lorddetson/piplayer/identification/MinifiedClientsTest.kt",
    "io/github/lorddetson/piplayer/fakes/**",
)

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

// The native components and bundled data of THIRD_PARTY_NOTICES.md, with their licence files in the assets (from :nativelib
// or this module); the open-source licences screen lists them after the Maven modules (docs/library.md §14.2).
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
    // Inside Maven modules, under licences of their own; their texts are in src/main/assets/licenses.
    NativeLibrary("SQLite (in androidx.sqlite:sqlite-bundled)", "bundled with 2.7.1", "Public domain", listOf("licenses/sqlite-public-domain.txt")),
    NativeLibrary(
        "Public Suffix List (in OkHttp)", "bundled with OkHttp", "Mozilla Public License 2.0",
        // MPL 2.0 §3.2(a): where the Source Code Form of the processed list is, then the licence.
        listOf("licenses/publicsuffix-source.txt", "licenses/publicsuffix-MPL-2.0.txt"),
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
        // The minified run's tests read no recordings, so it needs no fpcalc.
        if (!minifiedTests) {
            variant.androidTest?.sources?.assets
                ?.addGeneratedSourceDirectory(prepareRecordings, PrepareFingerprintSamples::outputDir)
        }
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
            inputs.file(rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md"))
                .withPropertyName("thirdPartyNotices").withPathSensitivity(PathSensitivity.NONE)
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
    dynamicFeatures += setOf(":radio")
    // Both app languages are available from Settings without downloading another split.
    bundle.language.enableSplit = false

    defaultConfig {
        applicationId = "io.github.lorddetson.piplayer"
        minSdk = 30
        // Explicit: AGP 9 would otherwise default targetSdk to compileSdk.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Turns the crash dialog off (docs/playback.md §7.4).
        testInstrumentationRunner = "io.github.lorddetson.piplayer.PiPlayerTestRunner"
        // Every build type carries the key, empty when there is none (TI §9); no flavors until M5.
        buildConfigField("String", "ACOUSTID_API_KEY", "\"$acoustIdApiKey\"")
        buildConfigField("String", "CONTACT_URL", "\"$contactUrl\"")
        ndk {
            // The native code (TagLib, Chromaprint) is built for these only; without the filter the APK would also
            // claim 32-bit ABIs through other libraries' .so files and crash there.
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            // R8 and resource shrinking (docs/library.md §2.1).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // The forum APK can add its own sources, manifest and dependencies (PIM-101).
        create("forum") {
            initWith(getByName("release"))
            signingConfig = null
            matchingFallbacks += "release"
        }
        // The release build signed with the debug key and profileable, for the Macrobenchmark checks (docs/library.md
        // §2.1); never published.
        create("benchmark") {
            initWith(getByName("release"))
            // A package of its own: a connected run uninstalls the app it tested, which must not be the debug build.
            applicationIdSuffix = ".benchmark"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            if (minifiedTests) {
                proguardFile(minifiedTestRules)
                testProguardFile("proguard-minified-test-apk.pro")
            }
        }
    }
    if (minifiedTests) {
        testBuildType = "benchmark"
        // The debug run's test package, which the instrumented test helper starts (with --no-install).
        defaultConfig.testApplicationId = "io.github.lorddetson.piplayer.test"
    }

    sourceSets {
        getByName("release") { kotlin.directories.add("src/play/kotlin") }
        listOf("debug", "forum", "benchmark").forEach { name ->
            getByName(name) { kotlin.directories.add("src/standalone/kotlin") }
        }
        // Both release-based builds use the release versions of the debug-only classes.
        listOf("benchmark", "forum").forEach { name ->
            getByName(name) { kotlin.directories.add("src/release/kotlin") }
        }
        // The fakes and fixtures of the online services, shared by the JVM and the instrumented tests.
        listOf("test", "androidTest").forEach { name ->
            getByName(name) {
                kotlin.directories.add("src/sharedTest/kotlin")
                resources.directories.add("src/sharedTest/resources")
            }
        }
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
            // A fork's contact, so that the User-Agent test compares BuildConfig with the value the fork set.
            providers.gradleProperty("piplayer.contactUrl").orNull?.let { test.systemProperty("piplayer.contactUrl", it) }
            // MinifiedTestRulesTest reads the keep rules of the minified run, so a change to them reruns the tests.
            test.inputs.file(minifiedTestRules).withPropertyName("minifiedTestRules")
                .withPathSensitivity(PathSensitivity.NONE)
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
    // The Play SDK is used by the Play bundle only. The public debug APK does not ship it.
    releaseImplementation(libs.play.feature.delivery)
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
    // Batch identification phases (a) and (b) (TI §8): one WorkManager job per album group.
    implementation(libs.androidx.work.runtime)
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
    // The test servers' own certificate (src/sharedTest), trusted only by the test clients.
    testImplementation(libs.okhttp.tls)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.assertj.core)
    // The fakes of the online services (src/sharedTest), served over HTTPS: cleartext stays blocked.
    androidTestImplementation(libs.okhttp.mockwebserver3)
    androidTestImplementation(libs.okhttp.tls)
    // The M0 Room spike (library search) also measures the platform's SQLite.
    androidTestImplementation(libs.androidx.sqlite.framework)
    androidTestImplementation(libs.room3.testing)
    kspAndroidTest(libs.room3.compiler)
}

// The minified run compiles only the tests written for it ([minifiedTests]); the list replaces the compiler's own
// patterns (every .kt and .java file), which an added include would only extend.
if (minifiedTests) {
    tasks.matching { it.name == "compileBenchmarkAndroidTestKotlin" }.configureEach {
        (this as PatternFilterable).setIncludes(minifiedTestSources)
    }
}
