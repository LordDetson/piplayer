package io.github.lorddetson.piplayer.crash

import io.github.lorddetson.piplayer.covers.SavedCovers
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/**
 * What goes into backups and device transfers (docs/library.md §3.3): an explicit list in both rule files, so that
 * everything else, crash reports included (docs/playback.md §7.2), stays on the device.
 */
class BackupRulesTest {

    private val sections = listOf(
        "backup_rules.xml" to "full-backup-content",
        "data_extraction_rules.xml" to "cloud-backup",
        "data_extraction_rules.xml" to "device-transfer",
    )

    private fun rules(file: String, section: String, tag: String): Set<Pair<String, String>> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/$file"))
        val root = document.documentElement
        val parent = if (root.tagName == section) root else root.getElementsByTagName(section).item(0) as Element
        val nodes = parent.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }.map { it.getAttribute("domain") to it.getAttribute("path") }.toSet()
    }

    @Test
    fun `backups and transfers take the user database and settings only`() {
        for ((file, section) in sections) {
            assertThat(rules(file, section, "include")).describedAs("$file $section").containsExactlyInAnyOrder(
                "database" to "user.db",
                "database" to "user.db-wal",
                "file" to "datastore/settings.preferences_pb",
                "sharedpref" to "app_language.xml",
            )
        }
    }

    @Test
    fun `saved covers lie in the no-backup files, which no rule includes`() {
        // TI §5.2, §10.3: accepted covers are re-fetched by release MBID after a restore, never backed up. The no-backup
        // files are `no_backup` in the app's data folder, which the `root` domain would reach.
        val noBackup = File("/data/user/0/app/no_backup")
        assertThat(SavedCovers.directory(noBackup)).isEqualTo(File(noBackup, SavedCovers.DIR))
        assertThat(SavedCovers.directory(noBackup).parentFile).isEqualTo(noBackup)
        val savedCovers = "no_backup/${SavedCovers.DIR}"
        for ((file, section) in sections) {
            val includes = rules(file, section, "include")
            assertThat(includes).describedAs("$file $section").noneMatch { (domain, path) ->
                domain == "root" && (path.isEmpty() || path == "." || savedCovers.startsWith(path.trimEnd('/')))
            }
            assertThat(includes.map { it.second }).describedAs("$file $section").noneMatch { it.contains(SavedCovers.DIR) }
        }
    }

    @Test
    fun `nothing is excluded from inside what is included`() {
        // With an include list, files that are not listed stay out; an exclude would only hide a mistake.
        for ((file, section) in sections) {
            assertThat(rules(file, section, "exclude")).describedAs("$file $section").isEmpty()
        }
    }
}
