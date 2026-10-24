package io.github.lorddetson.piplayer.crash

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/** Crash reports stay on the device: out of backups and transfers (docs/playback.md §7.2). */
class BackupRulesTest {

    private fun excludes(file: String, section: String): List<Pair<String, String>> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/$file"))
        val root = document.documentElement
        val parent = if (root.tagName == section) root else root.getElementsByTagName(section).item(0) as Element
        val nodes = parent.getElementsByTagName("exclude")
        return (0 until nodes.length).map { nodes.item(it) as Element }.map { it.getAttribute("domain") to it.getAttribute("path") }
    }

    @Test
    fun `the reports are excluded from backups and device transfers`() {
        assertThat(excludes("backup_rules.xml", "full-backup-content")).contains("file" to "crashes/")
        assertThat(excludes("data_extraction_rules.xml", "cloud-backup")).contains("file" to "crashes/")
        assertThat(excludes("data_extraction_rules.xml", "device-transfer")).contains("file" to "crashes/")
    }
}
