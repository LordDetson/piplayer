package io.github.lorddetson.piplayer.ui.customization

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.settings.ThemeMode
import io.github.lorddetson.piplayer.settings.UI_CANVAS_UNITS
import io.github.lorddetson.piplayer.settings.UiCanvasSettings
import io.github.lorddetson.piplayer.settings.UiPlacementMode
import io.github.lorddetson.piplayer.settings.UiSurface
import io.github.lorddetson.piplayer.settings.UiTextAlignment
import io.github.lorddetson.piplayer.ui.player.withStockPlayerTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditableUiPanelGestureTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun groupScalesTogetherAndSavesAlignmentAndPlacementWithTheTheme() =
        withStockPlayerTheme(TestFiles.targetContext, "ui_canvas_") {
            val surface = UiSurface.MINI_PLAYER
            val first = "fixture:first"
            val second = "fixture:second"
            val entry = "fixture_group_entry"
            compose.setContent {
                Box(Modifier.width(280.dp)) {
                    EditableUiPanel(
                        surface = surface,
                        themeId = ThemeMode.SYSTEM.stored,
                        components = listOf(first, second).map { id ->
                            UiCanvasComponent(id, R.string.player_element_track, width = 64.dp, alignableText = true) {
                                Text(id, modifier = Modifier.fillAlignedTextWidth(), textAlign = UiControlTextAlign())
                            }
                        },
                        initialRows = listOf(listOf(first, second)),
                        height = 160.dp,
                    ) {
                        val start = LocalUiEditEntry.current
                        if (start != null) {
                            Button(onClick = { start(first) }, modifier = Modifier.testTag(entry)) { Text("Edit") }
                        }
                    }
                }
            }
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(entry)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(entry).performClick()
            compose.onNodeWithTag("ui_edit_inspect").performClick()
            compose.onNodeWithTag("ui_edit_align_end").performScrollTo().performClick()
            compose.onNodeWithTag("ui_edit_apply_properties").performClick()
            compose.onNodeWithTag("ui_edit_group").performClick()
            compose.onNodeWithTag("ui_component_" + second).performClick()
            compose.onNodeWithTag("ui_edit_group").performClick()
            compose.onNodeWithTag("ui_edit_snap").performClick()
            compose.onNodeWithTag("ui_edit_options").performClick()
            compose.onNodeWithTag("ui_edit_placement_push").performClick()
            compose.onNodeWithTag("ui_edit_close_options").performClick()
            val before = compose.onNodeWithTag("ui_component_" + first).fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("ui_resize_" + second + ":right").performTouchInput {
                swipe(center, center + Offset(before.width, 0f), durationMillis = 400)
            }
            val after = compose.onNodeWithTag("ui_component_" + first).fetchSemanticsNode().boundsInRoot
            assertThat(after.width).isGreaterThan(before.width)
            compose.onNodeWithTag("ui_edit_done").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("ui_edit_done")).fetchSemanticsNodes().isEmpty() }

            val saved = runBlocking { UiCanvasSettings(TestFiles.targetContext).state.first().layouts.panels.getValue(surface) }
            assertThat(saved.groups).containsExactly(setOf(first, second))
            assertThat(saved.snapping).isFalse()
            assertThat(saved.placement).isEqualTo(UiPlacementMode.PUSH)
            assertThat(saved.items.first().style.textAlignment).isEqualTo(UiTextAlignment.END)
            assertThat(saved.items[0].bounds.width).isCloseTo(saved.items[1].bounds.width, within(1))
        }

    @Test
    fun draggingAndResizingSaveTheGeometryAndKeepTheLiveActionSilentUntilDone() =
        withStockPlayerTheme(TestFiles.targetContext, "ui_canvas_") {
            val surface = UiSurface.MINI_PLAYER
            val componentId = "fixture:play"
            val entryTag = "fixture_start_editing"
            val actionTag = "fixture_live_action"
            var actions = 0
            compose.setContent {
                Box(Modifier.width(280.dp)) {
                    EditableUiPanel(
                        surface = surface,
                        themeId = ThemeMode.SYSTEM.stored,
                        components = listOf(
                            UiCanvasComponent(componentId, R.string.player_play, width = 72.dp) {
                                Button(onClick = { actions++ }, modifier = Modifier.fillMaxSize().testTag(actionTag)) {
                                    Text("Play")
                                }
                            },
                        ),
                        initialRows = listOf(listOf(componentId)),
                        height = 160.dp,
                    ) {
                        val start = LocalUiEditEntry.current
                        if (start != null) {
                            Button(onClick = { start(componentId) }, modifier = Modifier.testTag(entryTag)) { Text("Edit") }
                        }
                    }
                }
            }
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(entryTag)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(entryTag).performClick()
            compose.onNodeWithTag("ui_editor_ring_handle").assertExists()
            compose.onNodeWithTag("ui_resize_fixture:play:top_left").assertExists()
            compose.onNodeWithTag("ui_resize_fixture:play:left").assertExists()
            val component = compose.onNodeWithTag("ui_component_$componentId")
            val initial = component.fetchSemanticsNode().boundsInRoot
            val frame = compose.onNodeWithTag("ui_canvas").fetchSemanticsNode().boundsInRoot
            val translation = Offset(frame.width / 4, frame.height / 4)
            component.performTouchInput {
                click()
                swipe(center, center + translation, durationMillis = 400)
            }
            val moved = component.fetchSemanticsNode().boundsInRoot
            assertThat(moved.left).isGreaterThan(initial.left + frame.width / 8)
            assertThat(moved.top).isGreaterThan(initial.top + frame.height / 8)

            val growth = Offset(frame.width / 8, frame.height / 6)
            compose.onNodeWithTag("ui_resize_$componentId").performTouchInput {
                swipe(center, center + growth, durationMillis = 400)
            }
            val resized = component.fetchSemanticsNode().boundsInRoot
            assertThat(resized.width).isGreaterThan(moved.width)
            assertThat(resized.height).isGreaterThan(moved.height)
            compose.runOnIdle { assertThat(actions).isZero() }

            compose.onNodeWithTag("ui_edit_done").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("ui_edit_done")).fetchSemanticsNodes().isEmpty() }
            val saved = runBlocking { UiCanvasSettings(TestFiles.targetContext).state.first().layouts.panels.getValue(surface) }
            val bounds = saved.items.single().bounds
            assertThat(bounds.x).isGreaterThan(125)
            assertThat(bounds.y).isGreaterThan(125)
            assertThat(bounds.width).isGreaterThan((initial.width / frame.width * UI_CANVAS_UNITS).toInt())
            assertThat(bounds.height).isGreaterThan((initial.height / frame.height * UI_CANVAS_UNITS).toInt())
            compose.onNodeWithTag(actionTag).performTouchInput { click() }
            compose.runOnIdle { assertThat(actions).isEqualTo(1) }
        }
}
