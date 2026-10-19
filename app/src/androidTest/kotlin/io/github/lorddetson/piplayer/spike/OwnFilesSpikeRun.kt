package io.github.lorddetson.piplayer.spike

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lorddetson.piplayer.TestFiles
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The own-files check of M0 (TI §11.4): runs the identification spike over every file under `own-files` in the app's
 * private files directory (folders included) and writes one JSON line per file to `spike/own-files.jsonl` next to it,
 * for the owner to mark each result right or wrong. A file that cannot be opened gets a line with its error. It sends
 * tags, file names and fingerprints to AcoustID and MusicBrainz, so it runs only with the instrumentation argument
 * `ownFiles=true`; scripts/run_own_files_spike.sh pushes the files, runs it and pulls the results.
 */
@RunWith(AndroidJUnit4::class)
class OwnFilesSpikeRun {

    @Test
    fun identifyOwnFiles() {
        assumeTrue(
            "Runs only with the instrumentation argument ownFiles=true",
            InstrumentationRegistry.getArguments().getString("ownFiles") == "true",
        )
        val context = TestFiles.targetContext
        val root = context.filesDir
        val folder = File(root, "own-files")
        val files = folder.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(folder).path }.toList()
        assertThat(files).describedAs("files under ${folder.path}").isNotEmpty()
        val out = File(root, "spike/own-files.jsonl").apply {
            parentFile?.mkdirs()
            delete()
        }

        val spike = SpikeServices.spike()
        out.bufferedWriter().use { writer ->
            files.forEachIndexed { index, file ->
                val path = file.relativeTo(folder).path
                val line = try {
                    val report = runBlocking { OpenedFile.open(context, file).use { spike.run(it) } }
                    Json.encodeToString(SpikeRecord.serializer(), SpikeReports.record(report).copy(file = path))
                } catch (e: Exception) {
                    buildJsonObject {
                        put("file", path)
                        put("error", "${e.javaClass.simpleName}: ${e.message}")
                    }.toString()
                }
                writer.appendLine(line)
                writer.flush()
                Log.i(TAG, "${index + 1}/${files.size} $path")
            }
        }
    }

    private companion object {
        const val TAG = "OwnFilesSpikeRun"
    }
}
