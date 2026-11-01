package io.github.lorddetson.piplayer.crash

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
    fun `backups and transfers take the user database and the settings file only`() {
        for ((file, section) in sections) {
            assertThat(rules(file, section, "include")).describedAs("$file $section").containsExactlyInAnyOrder(
                "database" to "user.db",
                "database" to "user.db-wal",
                "file" to "datastore/settings.preferences_pb",
            )
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
