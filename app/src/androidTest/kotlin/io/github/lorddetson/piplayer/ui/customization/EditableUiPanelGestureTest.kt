package io.github.lorddetson.piplayer.ui.customization

import android.os.SystemClock
import android.view.ViewConfiguration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.settings.ThemeMode
import io.github.lorddetson.piplayer.settings.AppearanceSettings
import io.github.lorddetson.piplayer.settings.UI_CANVAS_UNITS
import io.github.lorddetson.piplayer.settings.UiCanvasSettings
import io.github.lorddetson.piplayer.settings.UiCanvasState
import io.github.lorddetson.piplayer.settings.UiBuiltInGesture
import io.github.lorddetson.piplayer.settings.UiGesture
import io.github.lorddetson.piplayer.settings.UiGestureAction
import io.github.lorddetson.piplayer.settings.UiGestureActionId
import io.github.lorddetson.piplayer.settings.UiGestureBinding
import io.github.lorddetson.piplayer.settings.UiGestureEnvironment
import io.github.lorddetson.piplayer.settings.UiGestureProfile
import io.github.lorddetson.piplayer.settings.UiGestureSlot
import io.github.lorddetson.piplayer.settings.UiGestureTarget
import io.github.lorddetson.piplayer.settings.UiSurfaceGestures
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
import java.util.concurrent.atomic.AtomicInteger

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
            compose.onNodeWithTag("ui_edit_gestures").assertExists()
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
    fun recordedMovementReachesBothReassignedStockActionsAndRestoringIsThemeLocal() =
        withStockPlayerTheme(TestFiles.targetContext, "ui_canvas_") {
            val context = TestFiles.targetContext
            val settings = UiCanvasSettings(context)
            val appearance = AppearanceSettings(context)
            val first = "fixture:first"
            val second = "fixture:second"
            var clicks = 0
            lateinit var liveRuntime: UiGestureRuntime
            val stockClick: () -> Unit = { clicks++ }
            compose.setContent {
                val theme = settings.state.collectAsStateWithLifecycle<UiCanvasState?>(initialValue = null).value?.themeId
                Column(Modifier.width(320.dp)) {
                    EditableUiPanel(
                        UiSurface.MINI_PLAYER, theme,
                        listOf(first, second).map { id ->
                            UiCanvasComponent(id, if (id == first) R.string.player_play else R.string.player_next,
                                onClick = stockClick.takeIf { id == first }) { Text(id) }
                        },
                        initialRows = listOf(listOf(first, second)), height = 184.dp,
                    ) {
                        val open = LocalUiGestureEditEntry.current
                        val runtime = LocalUiGestureRuntime.current
                        if (runtime != null) SideEffect { liveRuntime = runtime }
                        if (open != null) Row {
                            StockUiSlot(first, onClick = stockClick, onHold = { open(first) }) {
                                Button(stockClick, Modifier.size(150.dp, 180.dp).testTag(first)) { Text("Play") }
                            }
                            StockUiSlot(second) {
                                Box(Modifier.size(150.dp, 180.dp).testTag(second))
                            }
                        }
                    }
                }
            }
            val play = compose.onNodeWithTag(first, useUnmergedTree = true)
            val alternative = compose.onNodeWithTag(second, useUnmergedTree = true)
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(first), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            play.performTouchInput { longClick() }
            choose("gesture_pattern", "gesture_pattern_long_press")
            compose.onNodeWithTag("gesture_remove").performScrollTo().performClick()
            compose.onNodeWithTag("gesture_apply").assertIsNotEnabled()

            choose("gesture_source", "gesture_source_$second")
            choose("gesture_pattern", "gesture_pattern_swipe_up")
            choose("gesture_action", "gesture_action_context_menu_$first")
            compose.onNodeWithTag("gesture_record").performScrollTo().performClick()
            compose.onNodeWithTag("gesture_name").performTextInput("Corner")
            compose.onNodeWithTag("gesture_record_pad").performTouchInput { drawCorner() }
            compose.onNodeWithTag("gesture_save_recording").performClick()
            choose("gesture_action", "gesture_action_stock_click_$first")
            choose("gesture_source", "gesture_source_$first")
            choose("gesture_pattern", "gesture_pattern_tap")
            compose.onNodeWithTag("gesture_remove").performScrollTo().performClick()
            saveGestures()

            val saved = runBlocking { settings.state.first().layouts.gestures }
            assertThat(saved.surfaces.getValue(UiSurface.MINI_PLAYER).unbound.map { it.gesture.builtIn })
                .containsExactlyInAnyOrder(UiBuiltInGesture.TAP, UiBuiltInGesture.LONG_PRESS)
            assertThat(saved.surfaces.getValue(UiSurface.MINI_PLAYER).bindings.map { it.action.id })
                .containsExactlyInAnyOrder(UiGestureActionId.CONTEXT_MENU, UiGestureActionId.STOCK_CLICK)
            compose.runOnIdle {
                assertThat(liveRuntime.enabled).isTrue()
                assertThat(liveRuntime.environment.components).contains(first, second)
                assertThat(liveRuntime.profile).isEqualTo(saved)
                assertThat(liveRuntime.exclusiveTap(first)).isTrue()
                assertThat(clicks).isZero()
            }
            play.performTouchInput { click() }
            compose.runOnIdle { assertThat(clicks).isZero() }
            alternative.performTouchInput { drawCorner() }
            compose.runOnIdle { assertThat(clicks).isEqualTo(1) }
            assertThat(saved.presets.single().name).isEqualTo("Corner")

            runBlocking { appearance.setTheme(ThemeMode.DARK) }
            compose.waitForIdle()
            play.performTouchInput { click() }
            compose.runOnIdle { assertThat(clicks).isEqualTo(2) }
            assertThat(runBlocking { settings.state.first().layouts.gestures.isEmpty }).isTrue()
            runBlocking { appearance.setTheme(ThemeMode.SYSTEM) }
            compose.waitForIdle()
            alternative.performTouchInput { swipeUp() }
            compose.onNodeWithTag("gesture_editor").assertExists()
            choose("gesture_pattern", "gesture_pattern_tap")
            compose.onNodeWithTag("gesture_restore").performScrollTo().performClick()
            choose("gesture_pattern", "gesture_pattern_long_press")
            compose.onNodeWithTag("gesture_restore").performScrollTo().performClick()
            saveGestures()
            play.performTouchInput { click() }
            compose.runOnIdle { assertThat(clicks).isEqualTo(3) }
            play.performTouchInput { longClick() }
            compose.onNodeWithTag("gesture_editor").assertExists()
        }

    @Test
    fun coldPanelRejectsRemovingTheLastReplacementForAHiddenStockMenu() =
        withStockPlayerTheme(TestFiles.targetContext, "ui_canvas_") {
            val first = "fixture:hidden"
            val second = "fixture:available"
            val surface = UiSurface.MINI_PLAYER
            val original = stockGesture(first, UiBuiltInGesture.LONG_PRESS,
                UiGestureAction(UiGestureActionId.CONTEXT_MENU, targetId = first))
            val replacement = UiGestureBinding(UiGestureSlot(UiGestureTarget.component(second),
                UiGesture.builtIn(UiBuiltInGesture.SWIPE_UP)), original.action)
            val profile = UiGestureProfile().editSurface(surface, UiSurfaceGestures().unbind(original.slot).assign(replacement))
            val settings = UiCanvasSettings(TestFiles.targetContext)
            assertThat(runBlocking {
                settings.saveGestures(ThemeMode.SYSTEM.stored, surface, null, profile,
                    UiGestureEnvironment(setOf(second), emptyList(), listOf(original)))
            }).isTrue()
            compose.setContent {
                Box(Modifier.width(280.dp)) {
                    EditableUiPanel(
                        surface, ThemeMode.SYSTEM.stored,
                        components = listOf(
                            UiCanvasComponent(first, R.string.player_play, stockContext = true) { Text("Hidden") },
                            UiCanvasComponent(second, R.string.player_next) { Text("Available") },
                        ),
                        initialRows = listOf(listOf(second)), height = 180.dp,
                    ) {
                        if (LocalUiGestureEditEntry.current != null) StockUiSlot(second) {
                            Box(Modifier.size(150.dp, 160.dp).testTag("cold_gesture_source"))
                        }
                    }
                }
            }
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasTestTag("cold_gesture_source"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("cold_gesture_source", useUnmergedTree = true).performTouchInput { swipeUp() }
            choose("gesture_pattern", "gesture_pattern_swipe_up")
            compose.onNodeWithTag("gesture_remove").performScrollTo().performClick()
            compose.onNodeWithTag("gesture_apply").assertIsNotEnabled()
        }

    @Test
    fun coldPanelSuppressesAnUnboundStockTapAndDispatchesItsReplacement() =
        withStockPlayerTheme(TestFiles.targetContext, "ui_canvas_") {
            val id = "fixture:cold_tap"
            val surface = UiSurface.MINI_PLAYER
            val action = UiGestureAction(UiGestureActionId.STOCK_CLICK, targetId = id)
            val tap = stockGesture(id, UiBuiltInGesture.TAP, action)
            val swipe = stockGesture(id, UiBuiltInGesture.SWIPE_UP, action)
            val profile = UiGestureProfile().editSurface(surface, UiSurfaceGestures().unbind(tap.slot).assign(swipe))
            assertThat(runBlocking {
                UiCanvasSettings(TestFiles.targetContext).saveGestures(ThemeMode.SYSTEM.stored, surface, null, profile,
                    UiGestureEnvironment(setOf(id), emptyList(), listOf(tap)))
            }).isTrue()
            var clicks = 0
            val stockClick: () -> Unit = { clicks++ }
            compose.setContent {
                EditableUiPanel(surface, ThemeMode.SYSTEM.stored,
                    listOf(UiCanvasComponent(id, R.string.player_play, onClick = stockClick) { Text("Play") }),
                    listOf(listOf(id)), 180.dp,
                ) {
                    if (LocalUiGestureEditEntry.current != null) StockUiSlot(id, onClick = stockClick) {
                        Button(stockClick, Modifier.size(140.dp, 160.dp).testTag("cold_stock_tap")) { Text("Play") }
                    }
                }
            }
            val source = compose.onNodeWithTag("cold_stock_tap", useUnmergedTree = true)
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasTestTag("cold_stock_tap"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            source.performTouchInput { click() }
            compose.runOnIdle { assertThat(clicks).isZero() }
            source.performTouchInput { swipeUp() }
            compose.runOnIdle { assertThat(clicks).isEqualTo(1) }
            val restored = profile.editSurface(surface, profile.surfaces.getValue(surface).restore(tap.slot))
            assertThat(runBlocking {
                UiCanvasSettings(TestFiles.targetContext).saveGestures(ThemeMode.SYSTEM.stored, surface, null, restored,
                    UiGestureEnvironment(setOf(id), emptyList(), listOf(tap)))
            }).isTrue()
            compose.waitForIdle()
            source.performTouchInput { click() }
            compose.runOnIdle { assertThat(clicks).isEqualTo(2) }
            source.performTouchInput { swipeUp() }
            compose.runOnIdle { assertThat(clicks).isEqualTo(3) }
        }

    @Test
    fun physicalDoubleTapTakesPrecedenceAndALoneTapStillRunsTheStockClick() =
        withStockPlayerTheme(TestFiles.targetContext, "ui_canvas_") {
            val surface = UiSurface.MINI_PLAYER
            val id = "fixture:double_tap"
            val stockTap = stockGesture(id, UiBuiltInGesture.TAP,
                UiGestureAction(UiGestureActionId.STOCK_CLICK, targetId = id))
            val doubleTap = stockGesture(id, UiBuiltInGesture.DOUBLE_TAP, UiGestureAction(UiGestureActionId.NEXT_TRACK))
            val profile = UiGestureProfile().editSurface(surface, UiSurfaceGestures().assign(doubleTap))
            assertThat(runBlocking {
                UiCanvasSettings(TestFiles.targetContext).saveGestures(ThemeMode.SYSTEM.stored, surface, null, profile,
                    UiGestureEnvironment(setOf(id), emptyList(), listOf(stockTap)))
            }).isTrue()

            val stockClicks = AtomicInteger()
            val nextCalls = AtomicInteger()
            val stockClick: () -> Unit = { stockClicks.incrementAndGet() }
            compose.setContent {
                CompositionLocalProvider(LocalUiGestureActionHandler provides { action ->
                    if (action.id != UiGestureActionId.NEXT_TRACK) false else {
                        nextCalls.incrementAndGet()
                        true
                    }
                }) {
                    EditableUiPanel(surface, ThemeMode.SYSTEM.stored,
                        listOf(UiCanvasComponent(id, R.string.player_play, onClick = stockClick) { Text("Play") }),
                        listOf(listOf(id)), 120.dp,
                    ) {
                        if (LocalUiGestureEditEntry.current != null) StockUiSlot(id, onClick = stockClick) {
                            Button(stockClick, Modifier.size(120.dp, 80.dp).testTag("physical_double_tap")) { Text("Play") }
                        }
                    }
                }
            }
            val target = compose.onNodeWithTag("physical_double_tap", useUnmergedTree = true)
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasTestTag("physical_double_tap"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            target.performTouchInput { click() }
            assertThat(stockClicks.get()).isZero()
            compose.waitUntil(2_000) { stockClicks.get() == 1 }
            assertThat(nextCalls.get()).isZero()

            target.performTouchInput {
                click()
                advanceEventTime(60)
                click()
            }
            compose.waitUntil(2_000) { nextCalls.get() == 1 }
            SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 35L)
            assertThat(stockClicks.get()).isEqualTo(1)
            assertThat(nextCalls.get()).isEqualTo(1)
        }

    @Test
    fun workspaceAssignmentTakesItsDirectionAndLeavesOtherScrollingNative() =
        withStockPlayerTheme(TestFiles.targetContext, "ui_canvas_") {
            val surface = UiSurface.LIBRARY_WORKSPACE
            val source = "workspace:content"
            val binding = stockGesture(source, UiBuiltInGesture.SWIPE_UP, UiGestureAction(UiGestureActionId.PLAY_PAUSE))
            val profile = UiGestureProfile().editSurface(surface, UiSurfaceGestures().assign(binding))
            val settings = UiCanvasSettings(TestFiles.targetContext)
            assertThat(runBlocking {
                settings.saveGestures(ThemeMode.SYSTEM.stored, surface, null, profile,
                    UiGestureEnvironment(setOf(source), emptyList(), emptyList()))
            }).isTrue()
            var calls = 0
            lateinit var scroll: ScrollState
            compose.setContent {
                scroll = rememberScrollState(initial = 400)
                CompositionLocalProvider(LocalUiGestureActionHandler provides { action ->
                    if (action.id != UiGestureActionId.PLAY_PAUSE) false else {
                        calls++
                        true
                    }
                }) {
                    EditableUiPanel(surface, ThemeMode.SYSTEM.stored,
                        listOf(UiCanvasComponent(source, R.string.ui_edit_workspace_content, preserveGestures = true) {}),
                        listOf(listOf(source)), 200.dp,
                    ) {
                        if (LocalUiGestureEditEntry.current != null) StockUiSlot(source) {
                            Column(Modifier.size(280.dp, 200.dp).testTag("gesture_scroll").verticalScroll(scroll)) {
                                Box(Modifier.height(1200.dp))
                            }
                        }
                    }
                }
            }
            val scrollable = compose.onNodeWithTag("gesture_scroll", useUnmergedTree = true)
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasTestTag("gesture_scroll"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            scrollable.performTouchInput { swipeDown() }
            val afterDown = compose.runOnIdle {
                assertThat(calls).isZero()
                scroll.value
            }
            assertThat(afterDown).isLessThan(400)
            scrollable.performTouchInput { swipeUp() }
            compose.runOnIdle {
                assertThat(calls).isEqualTo(1)
                assertThat(scroll.value).isEqualTo(afterDown)
            }
        }

    @Test
    fun scrubbingAGroupTimelineAndAnotherTargetUsesTheSameProgress() =
        withStockPlayerTheme(TestFiles.targetContext, "ui_canvas_") {
            val ids = listOf("fixture:first", "fixture:second", "fixture:third")
            compose.setContent {
                Box(Modifier.width(320.dp)) {
                    EditableUiPanel(
                        UiSurface.MINI_PLAYER, ThemeMode.SYSTEM.stored,
                        ids.map { id -> UiCanvasComponent(id, R.string.player_element_track) { Text(id) } },
                        initialRows = listOf(ids), height = 150.dp,
                    ) {
                        val open = LocalUiEditEntry.current
                        if (open != null) Button(onClick = { open(ids.first()) }, modifier = Modifier.testTag("group_entry")) { Text("Edit") }
                    }
                }
            }
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("group_entry")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("group_entry").performClick()
            compose.onNodeWithTag("ui_edit_group").performClick()
            compose.onNodeWithTag("ui_component_" + ids[1]).performClick()
            compose.onNodeWithTag("ui_edit_group").performClick()
            val components = ids.map { compose.onNodeWithTag("ui_component_$it") }
            val initial = components.map { it.fetchSemanticsNode().boundsInRoot }
            compose.onNodeWithTag("ui_edit_gestures").performClick()
            choose("gesture_pattern", "gesture_pattern_swipe_down")
            choose("gesture_action", "gesture_action_play_pause")
            choose("gesture_animation_target", "gesture_animation_target_" + ids.take(2).joinToString("+"))
            choose("gesture_animation", "gesture_animation_scale")
            choose("gesture_animation_target", "gesture_animation_target_" + ids.last())
            choose("gesture_animation", "gesture_animation_scale")
            compose.onNodeWithTag("gesture_keyframes").performScrollTo().performClick()
            compose.onNodeWithTag("gesture_keyframe_time").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
            compose.onNodeWithTag("gesture_add_keyframe").performScrollTo().performClick()
            compose.onNodeWithTag("gesture_delete_keyframe").performScrollTo().performClick()
            compose.onNodeWithTag("gesture_apply_keyframes").performClick()
            compose.onNodeWithTag("gesture_preview").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
            val preview = components.map { it.fetchSemanticsNode().boundsInRoot }
            preview.zip(initial).forEach { (shown, original) ->
                assertThat(shown.width / original.width).isCloseTo(0.78f, within(0.03f))
            }
            assertThat(preview[1].center.x - preview[0].center.x)
                .isCloseTo((initial[1].center.x - initial[0].center.x) * 0.78f, within(2f))
            saveGestures()
            val stored = runBlocking { UiCanvasSettings(TestFiles.targetContext).state.first().layouts.gestures }
            val timelines = stored.surfaces.getValue(UiSurface.MINI_PLAYER).bindings.single().timelines
            assertThat(timelines).hasSize(2)
            timelines.forEach { assertThat(it.keyframes.map { frame -> frame.progress }).containsExactly(0f, 1f) }
        }

    @Test
    fun anExplicitTapTimelineAnimatesAlongsideOneImmediateStockAction() =
        withStockPlayerTheme(TestFiles.targetContext, "ui_canvas_") {
            val id = "fixture:tap"
            var clicks = 0
            val stockClick: () -> Unit = { clicks++ }
            compose.setContent {
                Box(Modifier.width(280.dp)) {
                    EditableUiPanel(
                        UiSurface.MINI_PLAYER, ThemeMode.SYSTEM.stored,
                        components = listOf(UiCanvasComponent(id, R.string.player_play, onClick = stockClick) { Text("Play") }),
                        initialRows = listOf(listOf(id)), height = 160.dp,
                    ) {
                        val open = LocalUiGestureEditEntry.current
                        if (open != null) StockUiSlot(id, onClick = stockClick, onHold = { open(id) }) {
                            Button(stockClick, Modifier.size(132.dp, 88.dp).testTag("animated_tap")) { Text("Play") }
                        }
                    }
                }
            }
            val button = compose.onNodeWithTag("animated_tap", useUnmergedTree = true)
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasTestTag("animated_tap"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            button.performTouchInput { longClick() }
            choose("gesture_animation", "gesture_animation_scale")
            saveGestures()
            val before = button.fetchSemanticsNode().boundsInRoot.width
            compose.mainClock.autoAdvance = false
            try {
                button.performTouchInput { click() }
                compose.runOnIdle { assertThat(clicks).isEqualTo(1) }
                compose.mainClock.advanceTimeBy(80)
                val during = button.fetchSemanticsNode().boundsInRoot.width
                assertThat(during).isLessThan(before)
                compose.mainClock.advanceTimeBy(200)
                compose.runOnIdle { assertThat(clicks).isEqualTo(1) }
            } finally {
                compose.mainClock.autoAdvance = true
            }
        }

    private fun choose(anchor: String, option: String) {
        compose.onNodeWithTag(anchor).performScrollTo().performClick()
        compose.onNodeWithTag(option).performScrollTo().performClick()
    }

    private fun saveGestures() {
        compose.onNodeWithTag("gesture_apply").performClick()
        compose.onNodeWithTag("ui_edit_done").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("ui_edit_done")).fetchSemanticsNodes().isEmpty() }
    }

    private fun TouchInjectionScope.drawCorner() {
        val side = minOf(width, height)
        down(Offset(side * 0.18f, side * 0.22f))
        moveTo(Offset(side * 0.50f, side * 0.22f))
        moveTo(Offset(side * 0.82f, side * 0.22f))
        moveTo(Offset(side * 0.82f, side * 0.50f))
        moveTo(Offset(side * 0.82f, side * 0.78f))
        moveTo(Offset(side * 0.50f, side * 0.78f))
        moveTo(Offset(side * 0.24f, side * 0.78f))
        up()
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
