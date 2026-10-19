plugins {
    alias(libs.plugins.android.library)
}

/** Puts the licence texts of the bundled native code into the APK under assets/licenses. */
abstract class CopyLicenseAssets : DefaultTask() {
    /** Published file name to source file. */
    @get:Input
    abstract val names: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val target = outputDir.get().dir("licenses").asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        names.get().forEach { (name, source) -> File(source).copyTo(target.resolve(name)) }
    }
}

val prepareFingerprintSamples = tasks.register<PrepareFingerprintSamples>("prepareFingerprintSamples") {
    manifest = layout.projectDirectory.file("fingerprint-samples.json")
    script = rootProject.layout.projectDirectory.file("scripts/prepare_fingerprint_samples.py")
    mode = "pcm"
    fpcalc = fpcalcBinary()
    workingDirectory = rootProject.layout.projectDirectory
    python = providers.gradleProperty("piplayer.python")
        .orElse(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3")
}

val copyLicenseAssets = tasks.register<CopyLicenseAssets>("copyLicenseAssets") {
    val root = rootProject.layout.projectDirectory
    val licenses = mapOf(
        "THIRD_PARTY_NOTICES.md" to root.file("THIRD_PARTY_NOTICES.md"),
        "taglib-COPYING.LGPL" to root.file("third_party/taglib/COPYING.LGPL"),
        "utfcpp-LICENSE" to root.file("third_party/taglib/3rdparty/utfcpp/LICENSE"),
        "chromaprint-LICENSE.md" to root.file("third_party/chromaprint/LICENSE.md"),
        "kissfft-COPYING" to root.file("third_party/chromaprint/src/3rdparty/kissfft/COPYING"),
        "kissfft-BSD-3-Clause" to root.file("third_party/chromaprint/src/3rdparty/kissfft/LICENSES/BSD-3-Clause"),
        "libc++-LICENSE.txt" to layout.projectDirectory.file("licenses/libc++-LICENSE.txt"),
        "speexdsp-COPYING" to root.file("third_party/speexdsp/COPYING"),
        "speexdsp-resampler-NOTICE.txt" to layout.projectDirectory.file("licenses/speexdsp-resampler-NOTICE.txt"),
    )
    names = licenses.mapValues { (_, file) -> file.asFile.path }
    sources.from(licenses.values)
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyLicenseAssets, CopyLicenseAssets::outputDir)
        variant.androidTest?.sources?.assets
            ?.addGeneratedSourceDirectory(prepareFingerprintSamples, PrepareFingerprintSamples::outputDir)
    }
}

android {
    namespace = "io.github.lorddetson.piplayer.nativelib"
    compileSdk = 37
    // AGP 9.4 would otherwise build with its default NDK r28c.
    ndkVersion = "30.0.16248370"

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            // 64-bit phones and the x86_64 emulators; NDK 30 aligns both for 16 KB pages by default.
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                // libtag.so, the JNI library and the app share C++ types, so they share one C++ runtime.
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // AGP 9 would otherwise test against compileSdk; match the app.
        targetSdk = 36
        unitTests.all { it.useJUnitPlatform() }
    }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.assertj.core)
}
