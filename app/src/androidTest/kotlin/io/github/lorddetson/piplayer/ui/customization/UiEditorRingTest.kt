package io.github.lorddetson.piplayer.ui.customization

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiEditorRingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun editorActionsCollapseWithoutResizingTheCanvas() {
        val showRing = mutableStateOf(false)
        var clicks = 0
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(180.dp, 140.dp).testTag("editor_ring_canvas_fixture"))
                if (showRing.value) {
                    UiEditorRing(listOf(UiEditorAction(R.drawable.ic_add, R.string.ui_edit_add, "editor_ring_add_fixture") { clicks++ }))
                }
            }
        }
        val canvas = compose.onNodeWithTag("editor_ring_canvas_fixture")
        val before = canvas.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { showRing.value = true }
        val action = compose.onNodeWithTag("editor_ring_add_fixture")
        action.assertIsDisplayed()
            .assertContentDescriptionEquals(TestFiles.targetContext.getString(R.string.ui_edit_add))
        action.performClick()
        compose.runOnIdle { assertThat(clicks).isEqualTo(1) }

        val handle = compose.onNodeWithTag("ui_editor_ring_handle")
        handle.performClick()
        action.assertDoesNotExist()
        assertThat(canvas.fetchSemanticsNode().boundsInRoot).isEqualTo(before)
        handle.performClick()
        action.assertIsDisplayed()
        assertThat(canvas.fetchSemanticsNode().boundsInRoot).isEqualTo(before)
    }

    @Test
    fun draggingTheRingMovesAllCommandsWithoutMovingTheCanvas() {
        compose.setContent {
            Box(Modifier.fillMaxSize().testTag("editor_ring_canvas_fixture")) {
                UiEditorRing(listOf(UiEditorAction(R.drawable.ic_add, R.string.ui_edit_add, "editor_ring_add_fixture") {}))
            }
        }
        val canvas = compose.onNodeWithTag("editor_ring_canvas_fixture")
        val handle = compose.onNodeWithTag("ui_editor_ring_handle")
        val action = compose.onNodeWithTag("editor_ring_add_fixture")
        val canvasBefore = canvas.fetchSemanticsNode().boundsInRoot
        val handleBefore = handle.fetchSemanticsNode().layoutInfo.coordinates.localToScreen(Offset.Zero)
        val actionBefore = action.fetchSemanticsNode().layoutInfo.coordinates.localToScreen(Offset.Zero)

        handle.performTouchInput { swipe(center, center - Offset(64.dp.toPx(), 80.dp.toPx()), durationMillis = 400) }

        val handleAfter = handle.fetchSemanticsNode().layoutInfo.coordinates.localToScreen(Offset.Zero)
        val actionAfter = action.fetchSemanticsNode().layoutInfo.coordinates.localToScreen(Offset.Zero)
        assertThat(handleAfter.x).isLessThan(handleBefore.x - 20f)
        assertThat(handleAfter.y).isLessThan(handleBefore.y - 20f)
        assertThat(actionAfter - handleAfter).isEqualTo(actionBefore - handleBefore)
        assertThat(canvas.fetchSemanticsNode().boundsInRoot).isEqualTo(canvasBefore)
    }

    @Test
    fun swipingAnOrbitCommandRotatesTheArcAroundTheEditorHandle() {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                UiEditorRing(List(8) { index ->
                    UiEditorAction(R.drawable.ic_add, R.string.ui_edit_add, "orbit_command_" + index) {}
                })
            }
        }
        val handle = compose.onNodeWithTag("ui_editor_ring_handle")
        val top = compose.onNodeWithTag("orbit_command_0")
        val next = compose.onNodeWithTag("orbit_command_1")
        val handleBefore = handle.fetchSemanticsNode().layoutInfo.coordinates.localToScreen(Offset.Zero)
        val topBefore = top.fetchSemanticsNode().layoutInfo.coordinates.localToScreen(Offset.Zero)

        top.performTouchInput { swipe(center, center - Offset(66.dp.toPx(), 0f), durationMillis = 400) }

        val topAfter = top.fetchSemanticsNode().layoutInfo.coordinates.localToScreen(Offset.Zero)
        val nextAfter = next.fetchSemanticsNode().layoutInfo.coordinates.localToScreen(Offset.Zero)
        assertThat(topAfter.x).isLessThan(topBefore.x - 20f)
        assertThat(nextAfter.x).isLessThan(topAfter.x)
        assertThat(handle.fetchSemanticsNode().layoutInfo.coordinates.localToScreen(Offset.Zero)).isEqualTo(handleBefore)
    }
}
