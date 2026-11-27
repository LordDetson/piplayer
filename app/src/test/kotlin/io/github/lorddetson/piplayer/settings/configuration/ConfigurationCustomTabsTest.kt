package io.github.lorddetson.piplayer.settings.configuration

import androidx.datastore.preferences.core.edit
import io.github.lorddetson.piplayer.settings.HiddenFiles
import io.github.lorddetson.piplayer.settings.MemoryDataStore
import io.github.lorddetson.piplayer.settings.UiBuiltInGesture
import io.github.lorddetson.piplayer.settings.UiCanvasBounds
import io.github.lorddetson.piplayer.settings.UiCanvasItem
import io.github.lorddetson.piplayer.settings.UiCanvasLayout
import io.github.lorddetson.piplayer.settings.UiGesture
import io.github.lorddetson.piplayer.settings.UiGestureAction
import io.github.lorddetson.piplayer.settings.UiGestureActionId
import io.github.lorddetson.piplayer.settings.UiGestureBinding
import io.github.lorddetson.piplayer.settings.UiGestureMotionPreset
import io.github.lorddetson.piplayer.settings.UiGestureProfile
import io.github.lorddetson.piplayer.settings.UiGestureSlot
import io.github.lorddetson.piplayer.settings.UiGestureTarget
import io.github.lorddetson.piplayer.settings.UiSurface
import io.github.lorddetson.piplayer.settings.UiSurfaceGestures
import io.github.lorddetson.piplayer.settings.UiThemeCanvases
import io.github.lorddetson.piplayer.settings.UiCanvasSettings
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryTabsSettings
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryTabWrite
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ConfigurationCustomTabsTest {
    private val file = MemoryDataStore()
    private val store = ConfigurationStore(file, FixedLocale, { "installation" })

    @Test
    fun `version two preserves tab ids default order and rules with portable source hints`() {
        val parsed = ConfigurationCodec.decode(portableDocument().byteInputStream())
        assertThat(parsed).isInstanceOf(ConfigurationRead.Loaded::class.java)
        val encoded = ConfigurationCodec.encode((parsed as ConfigurationRead.Loaded).patch)
        assertThat(encoded.decodeToString()).contains(
            "\"version\": 2", playlistTab, rulesTab, "\"sourceRef\"", "\"Road trip\"", "\"composer\"", "\"customTabOrder\"",
        ).doesNotContain("\"playlistId\"", "\"folderKey\"", "\"installation\"")
        assertThat(ConfigurationCodec.decode(encoded.inputStream())).isEqualTo(parsed)
    }

    @Test
    fun `portable source records reject local identifiers unknown fields and invalid filters`() {
        for (text in listOf(
            portableDocument().replace("\"hint\": \"Road trip\"", "\"hint\": \"Road trip\", \"playlistId\": 7"),
            portableDocument().replace("\"hint\": \"Road trip\"", "\"hint\": \"Road trip\", \"folderKey\": \"primary:Music/\""),
            portableDocument().replace("\"hint\": \"Road trip\"", "\"hint\": \"Road trip\", \"installation\": \"other\""),
            portableDocument().replace("\"contains\"", "\"wildcard\""),
            portableDocument().replace("\"contains\"", "7"),
            portableDocument().replace("\"text\": \"Bach\"", "\"text\": \"\""),
            portableDocument().replace("\"version\": 2", "\"version\": 1"),
        )) {
            assertThat(ConfigurationCodec.decode(text.byteInputStream())).describedAs(text)
                .isEqualTo(ConfigurationRead.Invalid)
        }
        val original = """{"version":1,"library":{"showRecordings":true}}"""
        assertThat(ConfigurationCodec.decode(original.byteInputStream())).isInstanceOf(ConfigurationRead.Loaded::class.java)
    }

    @Test
    fun `a clean store applies portable tabs only after preview and undo preserves unrelated changes`() = runBlocking<Unit> {
        val before = file.data.first()
        val preview = store.preview(portableDocument().byteInputStream())
        assertThat(preview).isInstanceOf(ConfigurationPreviewResult.Ready::class.java)
        val changes = (preview as ConfigurationPreviewResult.Ready).preview.changes
        assertThat(changes.map { it.path }).contains("library.customTabs.$playlistTab", "library.customTabs.$rulesTab")
        assertThat(file.data.first()).isEqualTo(before)

        val applied = store.apply(preview.preview) as ConfigurationWriteResult.Applied
        val exported = store.export().decodeToString()
        assertThat(exported).contains("\"version\": 2", playlistTab, rulesTab, "Road trip", "Bach")
        assertThat(store.preview(exported.byteInputStream())).isInstanceOf(ConfigurationPreviewResult.Ready::class.java)
        file.edit { it[HiddenFiles.SHOW_RECORDINGS] = true }
        assertThat(store.undo(applied.undo)).isInstanceOf(ConfigurationWriteResult.Restored::class.java)
        assertThat(store.export().decodeToString()).doesNotContain(playlistTab, rulesTab)
        assertThat(file.data.first()[HiddenFiles.SHOW_RECORDINGS]).isTrue()
    }

    @Test
    fun `a theme cannot import an unknown tab or hide all effective tabs`() = runBlocking<Unit> {
        for (text in listOf(
            layoutDocument(unknownTab, ""),
            layoutDocument(playlistTab, "songs,albums,artists,playlists,genres,folders,$playlistTab,$rulesTab"),
        )) {
            assertThat(store.preview(text.byteInputStream())).isEqualTo(ConfigurationPreviewResult.Invalid)
            assertThat(file.data.first().asMap()).isEmpty()
        }
    }

    @Test
    fun `a patch can place a tab added by the same document and undo both together`() = runBlocking<Unit> {
        val text = layoutDocument(playlistTab, "albums")
        val prepared = store.preview(text.byteInputStream()) as ConfigurationPreviewResult.Ready
        val applied = store.apply(prepared.preview) as ConfigurationWriteResult.Applied
        assertThat(store.export().decodeToString()).contains("\"tabOrder\": \"$playlistTab,songs")
        assertThat(store.undo(applied.undo)).isInstanceOf(ConfigurationWriteResult.Restored::class.java)
        assertThat(file.data.first().asMap()).isEmpty()
    }

    @Test
    fun `an animation on a hidden custom tab stays in a portable configuration`() = runBlocking<Unit> {
        val initial = store.preview(portableDocument().byteInputStream()) as ConfigurationPreviewResult.Ready
        assertThat(store.apply(initial.preview)).isInstanceOf(ConfigurationWriteResult.Applied::class.java)
        val origin = UiGestureTarget.component("tab:songs")
        val target = UiGestureTarget.component("tab:$playlistTab")
        val binding = UiGestureBinding(
            UiGestureSlot(origin, UiGesture.builtIn(UiBuiltInGesture.SWIPE_LEFT)),
            UiGestureAction(UiGestureActionId.NEXT_TRACK),
            listOf(UiGestureMotionPreset.CARDS.timeline(target)),
        )
        val profile = UiGestureProfile(surfaces = mapOf(UiSurface.LIBRARY to UiSurfaceGestures(bindings = listOf(binding))))
        file.edit { UiCanvasSettings.store(it, "system", UiThemeCanvases(gestures = profile)) }
        assertThat(LibraryTabsSettings(file).setVisible(playlistTab, false)).isEqualTo(LibraryTabWrite.STORED)

        val otherFile = MemoryDataStore()
        val other = ConfigurationStore(otherFile, FixedLocale, { "new-installation" })
        val snapshot = store.export()
        val preview = other.preview(snapshot.inputStream())
        assertThat(preview).isInstanceOf(ConfigurationPreviewResult.Ready::class.java)
        assertThat(other.apply((preview as ConfigurationPreviewResult.Ready).preview))
            .isInstanceOf(ConfigurationWriteResult.Applied::class.java)
        assertThat(UiCanvasSettings.savedLayouts(otherFile.data.first(), "system").gestures).isEqualTo(profile)
        assertThat(playlistTab).isIn(LibraryTabsSettings(otherFile).layout.first().hidden)
        assertThat(other.export()).isEqualTo(snapshot)
    }

    @Test
    fun `the visible stock footer tab keeps its gesture in an absolute library layout`() = runBlocking<Unit> {
        val initial = store.preview(portableDocument().byteInputStream()) as ConfigurationPreviewResult.Ready
        assertThat(store.apply(initial.preview)).isInstanceOf(ConfigurationWriteResult.Applied::class.java)
        val tabs = LibraryTabsSettings(file)
        assertThat(tabs.setVisible(playlistTab, false)).isEqualTo(LibraryTabWrite.STORED)
        assertThat(tabs.setVisible(rulesTab, false)).isEqualTo(LibraryTabWrite.STORED)
        val origin = UiGestureTarget.component("tab:songs")
        val binding = UiGestureBinding(
            UiGestureSlot(origin, UiGesture.builtIn(UiBuiltInGesture.SWIPE_LEFT)),
            UiGestureAction(UiGestureActionId.STOCK_CLICK, targetId = "tab:songs"),
        )
        val tap = UiGestureSlot(origin, UiGesture.builtIn(UiBuiltInGesture.TAP))
        val profile = UiGestureProfile(surfaces = mapOf(
            UiSurface.LIBRARY to UiSurfaceGestures(bindings = listOf(binding), unbound = setOf(tap)),
        ))
        val canvas = UiCanvasLayout(listOf(UiCanvasItem("tab:$playlistTab", UiCanvasBounds(0, 0, 120, 50))))
        file.edit { UiCanvasSettings.store(it, "system", UiThemeCanvases(mapOf(UiSurface.LIBRARY to canvas), profile)) }

        suspend fun importAndCheckGesture() {
            val otherFile = MemoryDataStore()
            val other = ConfigurationStore(otherFile, FixedLocale, { "new-installation" })
            val preview = other.preview(store.export().inputStream())
            assertThat(preview).isInstanceOf(ConfigurationPreviewResult.Ready::class.java)
            assertThat(other.apply((preview as ConfigurationPreviewResult.Ready).preview))
                .isInstanceOf(ConfigurationWriteResult.Applied::class.java)
            assertThat(UiCanvasSettings.savedLayouts(otherFile.data.first(), "system").gestures).isEqualTo(profile)
        }
        importAndCheckGesture()
        assertThat(tabs.setVisible(playlistTab, true)).isEqualTo(LibraryTabWrite.STORED)
        importAndCheckGesture()
        assertThat(tabs.setVisible(playlistTab, false)).isEqualTo(LibraryTabWrite.STORED)
        assertThat(tabs.move("albums", 0)).isEqualTo(LibraryTabWrite.STORED)
        importAndCheckGesture()
    }

    private fun portableDocument() = """
        {
          "version": 2,
          "library": {
            "customTabs": {
              "$playlistTab": {
                "name": "Road",
                "source": {"type": "playlist", "sourceRef": "10000000-0000-4000-8000-000000000001", "hint": "Road trip"}
              },
              "$rulesTab": {
                "name": "Bach",
                "source": {"type": "rules", "match": "any", "rules": [{"field": "composer", "comparison": "contains", "text": "Bach"}]}
              }
            },
            "customTabOrder": ["$rulesTab", "$playlistTab"]
          }
        }
    """.trimIndent()

    private fun layoutDocument(tabId: String, hidden: String) = portableDocument().dropLast(1) + """
        ,
        "appearance": {"builtInOverrides": {"system": {"library": {
          "tabOrder": "$tabId,songs", "hiddenTabs": "$hidden", "buttonOrder": "", "hiddenButtons": ""
        }}}}
        }
    """.trimIndent()

    private object FixedLocale : ConfigurationLocale {
        override val backend = "platform"
        override fun read() = ""
        override fun write(value: String?) = false
    }

    private companion object {
        const val playlistTab = "custom-00000000-0000-4000-8000-000000000001"
        const val rulesTab = "custom-00000000-0000-4000-8000-000000000002"
        const val unknownTab = "custom-00000000-0000-4000-8000-000000000003"
    }
}
