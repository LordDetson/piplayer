import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/**
 * Downloads the fingerprint test recordings (once, into a cache outside the repository) and prepares them as
 * androidTest assets with scripts/prepare_fingerprint_samples.py: in [mode] "pcm" as 11025 Hz PCM with fpcalc's
 * fingerprint of that PCM, in mode "files" as the recordings themselves with fpcalc's fingerprint of each file. Needs
 * Python, ffmpeg and fpcalc; only androidTest builds that package their assets run it (compiling the tests does not).
 */
abstract class PrepareFingerprintSamples : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val script: RegularFileProperty

    @get:Input
    abstract val mode: Property<String>

    /** fpcalc 1.6.2, whose output is the reference: a new binary makes new references. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val fpcalc: RegularFileProperty

    @get:Internal
    abstract val workingDirectory: DirectoryProperty

    /** "python" on Windows, "python3" elsewhere; -Ppiplayer.python=... picks another interpreter. */
    @get:Input
    abstract val python: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun prepare() {
        execOperations.exec {
            workingDir(workingDirectory)
            commandLine(
                python.get(), "-I", script.get().asFile.path,
                "--manifest", manifest.get().asFile.path,
                "--mode", mode.get(),
                "--fpcalc", fpcalc.get().asFile.path,
                "--out", outputDir.get().asFile.path,
            )
        }
    }
}

/** The FPCALC environment variable, or else the copy of fpcalc under tools/ (not in git). */
fun Project.fpcalcBinary(): Provider<RegularFile> {
    val root = rootProject.layout.projectDirectory
    return providers.environmentVariable("FPCALC").map { root.file(it) }
        .orElse(root.file("tools/fpcalc-1.6.2/chromaprint-fpcalc-1.6.2-windows-x86_64/fpcalc.exe"))
}
