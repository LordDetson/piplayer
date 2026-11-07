import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider

/**
 * Writes the open-source licence list of one variant into its assets (docs/library.md §14.2): `licenses/dependencies.json`
 * with every module of the runtime classpath and its licence, read from its POM or a parent POM, plus the native
 * components, and one text per licence kind under `licenses/kinds/`. A module whose licence the build does not know, or
 * whose kind needs a copyright notice that is not given, fails the task.
 */
abstract class CollectDependencyLicenses : DefaultTask() {

    /** "group:name:version" of every module of the runtime classpath. */
    @get:Input
    abstract val components: ListProperty<String>

    /** The modules' POMs and their parents', several levels up. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val poms: ConfigurableFileCollection

    /** Copyright notices by "group:name", for the kinds whose text asks for one. */
    @get:Input
    abstract val notices: MapProperty<String, String>

    /** The native components, as [NativeLibrary.encode] writes them. */
    @get:Input
    abstract val nativeLibraries: ListProperty<String>

    /** One text per licence kind, named `<SPDX id>.txt`. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val kindTexts: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun collect() {
        val facts = poms.files.filter { it.name.endsWith(".pom") }.map(Poms::read)
            .filter { it.coordinates != null }.associateBy { it.coordinates!! }
        val libraries = try {
            DependencyLicenses.resolve(components.get().map(Coordinates::parse), facts, notices.get())
        } catch (e: LicenseProblems) {
            throw org.gradle.api.GradleException(e.message!!)
        }
        val kinds = libraries.flatMap { it.kinds }.distinct()
        val missingTexts = kinds.filter { !kindTexts.get().file("${it.id}.txt").asFile.isFile }
        if (missingTexts.isNotEmpty()) {
            throw org.gradle.api.GradleException(
                "No text for the licence kinds ${missingTexts.joinToString { it.id }} in ${kindTexts.get().asFile}",
            )
        }
        val target = outputDir.get().dir(ASSET_FOLDER).asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        val kindsFolder = target.resolve(KINDS_FOLDER).apply { mkdirs() }
        kinds.forEach { kind -> kindTexts.get().file("${kind.id}.txt").asFile.copyTo(kindsFolder.resolve("${kind.id}.txt")) }
        target.resolve(LIST_FILE).writeText(
            DependencyLicenses.json(libraries, nativeLibraries.get().map(NativeLibrary::decode), "$ASSET_FOLDER/$KINDS_FOLDER"),
        )
    }

    companion object {
        const val ASSET_FOLDER = "licenses"
        const val KINDS_FOLDER = "kinds"
        const val LIST_FILE = "dependencies.json"
    }
}

/**
 * Writes "group:name:version" of every module of a runtime classpath, one per line: what the JVM check of the licence
 * list compares the list with (docs/library.md §14.4).
 */
abstract class ListRuntimeComponents : DefaultTask() {

    @get:Input
    abstract val components: ListProperty<String>

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun list() {
        output.get().asFile.writeText(components.get().sorted().joinToString("\n", postfix = "\n"))
    }
}

/** The modules of [runtime]'s graph, as "group:name:version"; projects (PiPlayer's own modules) are left out. */
fun moduleComponents(runtime: Configuration): Provider<List<String>> =
    runtime.incoming.resolutionResult.rootComponent.map { root ->
        val seen = LinkedHashSet<ResolvedComponentResult>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            if (!seen.add(component)) continue
            component.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                .forEach { queue += it.selected }
        }
        seen.mapNotNull { it.id as? ModuleComponentIdentifier }
            .map { "${it.group}:${it.module}:${it.version}" }
            .distinct()
    }

/** Parent POMs are looked for this many levels up; Guava's licence, for one, is in its first parent. */
private const val POM_LEVELS = 4

/**
 * The POMs of [runtime]'s modules and of their parents up to [POM_LEVELS] levels, worked out only when the files are
 * asked for. A configuration keeps one version of a module, so two modules with different versions of one parent (Guava's
 * failureaccess and Guava itself) have their parents fetched by separate detached configurations.
 */
fun Project.licensePoms(runtime: Configuration): FileCollection {
    val components = moduleComponents(runtime)
    val dependencies = dependencies
    val configurations = configurations
    return files(
        provider {
            val found = mutableListOf<java.io.File>()
            val seen = HashSet<Coordinates>()
            var wanted = components.get().map(Coordinates::parse)
            repeat(POM_LEVELS) {
                val batches = wanted.filter(seen::add).groupBy { "${it.group}:${it.name}" }.values
                    .flatMap { versions -> versions.withIndex() }
                    .groupBy({ it.index }, { it.value })
                    .values
                val files = batches.flatMap { batch ->
                    val configuration = configurations.detachedConfiguration(
                        *batch.map { dependencies.create("$it@pom") }.toTypedArray(),
                    ).apply { isTransitive = false }
                    // A POM a repository lacks is left out, so that the list reports what it misses.
                    configuration.incoming.artifactView { lenient(true) }.files.files
                }
                found += files
                wanted = files.mapNotNull { Poms.read(it).parent }.distinct()
                if (wanted.isEmpty()) return@provider found
            }
            found
        },
    )
}

/**
 * Registers the licence list of the variant [variantName] (docs/library.md §14.2) and the list of its runtime modules
 * for the JVM check; returns both.
 */
fun Project.registerDependencyLicenses(
    variantName: String,
    runtime: Configuration,
    kindTexts: Directory,
    notices: Map<String, String>,
    natives: List<NativeLibrary>,
): Pair<TaskProvider<CollectDependencyLicenses>, TaskProvider<ListRuntimeComponents>> {
    val capitalized = variantName.replaceFirstChar { it.uppercase() }
    val collect = tasks.register("collect${capitalized}DependencyLicenses", CollectDependencyLicenses::class.java) {
        components.set(moduleComponents(runtime))
        poms.from(licensePoms(runtime))
        this.notices.set(notices)
        nativeLibraries.set(natives.map(NativeLibrary::encode))
        this.kindTexts.set(kindTexts)
        outputDir.set(layout.buildDirectory.dir("generated/licenses/$variantName"))
    }
    val list = tasks.register("list${capitalized}RuntimeComponents", ListRuntimeComponents::class.java) {
        components.set(moduleComponents(runtime))
        output.set(layout.buildDirectory.file("generated/licenses/$variantName-components.txt"))
    }
    return collect to list
}
