package io.github.lorddetson.piplayer.settings

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UiGestureProfileTest {
    private val surface = UiSurface.NOW_PLAYING
    private val cover = UiGestureTarget.component("player:cover")
    private val title = UiGestureTarget.component("player:title")
    private val play = UiGestureTarget.component("player:play")
    private val group = UiGestureTarget.group(listOf("player:title", "player:cover"))
    private val coverMenu = UiGestureAction(UiGestureActionId.CONTEXT_MENU, "player", "player:cover")
    private val titleMenu = coverMenu.copy(targetId = "player:title")
    private val pause = UiGestureAction(UiGestureActionId.PLAY_PAUSE)
    private val coverHold = slot(cover, UiBuiltInGesture.LONG_PRESS)
    private val titleHold = slot(title, UiBuiltInGesture.LONG_PRESS)
    private val groupSwipe = slot(group, UiBuiltInGesture.SWIPE_LEFT)
    private val environment = UiGestureEnvironment(
        components = setOf("player:cover", "player:title", "player:play"),
        groups = listOf(setOf("player:cover", "player:title")),
        stock = listOf(UiGestureBinding(coverHold, coverMenu), UiGestureBinding(titleHold, titleMenu)),
    )

    @Test
    fun `stock is effective as is and scoped context menus require their own replacements`() {
        val empty = UiGestureProfile()
        assertThat(empty.resolve(surface, environment)).containsExactlyElementsOf(environment.stock)
        val override = UiSurfaceGestures().assign(UiGestureBinding(coverHold, pause))
        assertThat(profile(override).validate(surface, environment)).isNotNull
        val wrongScope = override.assign(UiGestureBinding(groupSwipe, titleMenu))
        assertThat(profile(wrongScope).validate(surface, environment)).isNotNull
        val replacement = wrongScope.assign(UiGestureBinding(groupSwipe, coverMenu))
        assertThat(profile(replacement).validate(surface, environment)).isNull()
        assertThat(profile(replacement).resolve(surface, environment).associate { it.slot to it.action })
            .containsEntry(coverHold, pause).containsEntry(groupSwipe, coverMenu).containsEntry(titleHold, titleMenu)
        assertThat(profile(replacement.restore(coverHold)).resolve(surface, environment).map { it.action })
            .containsExactlyInAnyOrder(coverMenu, coverMenu, titleMenu)
    }

    @Test
    fun `reachable duplicates cover a displaced action independently`() {
        val playTap = slot(play, UiBuiltInGesture.DOUBLE_TAP)
        val original = UiSurfaceGestures().unbind(coverHold)
        val duplicate = original.assign(UiGestureBinding(playTap, coverMenu)).assign(UiGestureBinding(groupSwipe, coverMenu))
        assertThat(profile(duplicate).validate(surface, environment)).isNull()
        assertThat(profile(duplicate).resolve(surface, environment).count { it.action == coverMenu }).isEqualTo(2)
        assertThat(profile(duplicate.restore(playTap)).validate(surface, environment)).isNull()
        assertThat(profile(duplicate.restore(playTap).restore(groupSwipe)).validate(surface, environment)).isNotNull
        assertThat(profile(original.restore(coverHold)).validate(surface, environment)).isNull()
    }

    @Test
    fun `an exact group expires on membership change and losing its only replacement is invalid`() {
        val original = profile(UiSurfaceGestures().unbind(coverHold).assign(UiGestureBinding(groupSwipe, coverMenu)))
        assertThat(group.members).containsExactly("player:cover", "player:title")
        assertThat(UiGestureTarget.group(listOf("player:cover", "player:title"))).isEqualTo(group)
        val edited = environment.copy(groups = listOf(setOf("player:cover", "player:title", "player:play")))
        val pruned = original.prune(surface, edited)
        assertThat(pruned.resolve(surface, edited).map { it.slot }).containsExactly(titleHold)
        assertThat(pruned.validate(surface, edited, original)).isNotNull
        val hidden = environment.copy(components = environment.components - "player:cover")
        assertThat(original.prune(surface, hidden).validate(surface, hidden, original)).isNotNull
        assertThat(UiGestureProfile().validate(surface, hidden)).isNull()
    }

    @Test
    fun `bindings cannot share a slot and one animation cannot move a group member twice`() {
        val command = UiGestureBinding(coverHold, pause)
        val duplicates = UiSurfaceGestures(bindings = listOf(command, command.copy(action = coverMenu)))
        assertThat(profile(duplicates).isValid).isFalse()
        assertThat(profile(UiSurfaceGestures(bindings = listOf(command), unbound = setOf(coverHold))).isValid).isFalse()
        val groupSlide = UiGestureMotionPreset.SLIDE.timeline(group)
        val coverFade = UiGestureMotionPreset.FADE.timeline(cover)
        val playScale = UiGestureMotionPreset.SCALE.timeline(play)
        assertThat(profile(UiSurfaceGestures().assign(command.copy(timelines = listOf(groupSlide, coverFade)))).isValid).isFalse()
        assertThat(profile(UiSurfaceGestures().assign(command.copy(timelines = listOf(groupSlide, playScale)))).isValid).isTrue()
        assertThat(profile(UiSurfaceGestures().assign(command.copy(timelines = listOf(coverFade, coverFade)))).isValid).isFalse()
    }

    @Test
    fun `pruning removes stale animation targets while preserving the reachable command`() {
        val tap = slot(cover, UiBuiltInGesture.DOUBLE_TAP)
        val binding = UiGestureBinding(tap, pause, listOf(UiGestureMotionPreset.CARDS.timeline(group),
            UiGestureMotionPreset.FADE.timeline(cover)))
        val edited = environment.copy(groups = emptyList())
        val resolved = profile(UiSurfaceGestures().assign(binding)).prune(surface, edited).resolve(surface, edited)
        assertThat(resolved.first { it.slot == tap }.timelines).containsExactly(UiGestureMotionPreset.FADE.timeline(cover))
    }

    @Test
    fun `explicit group gestures outrank stock members and must preserve actions they shadow`() {
        val groupHold = slot(group, UiBuiltInGesture.LONG_PRESS)
        val override = UiSurfaceGestures().assign(UiGestureBinding(groupHold, pause))
        assertThat(profile(override).resolveFor(surface, environment, cover.members.single()).map { it.action })
            .containsExactly(pause)
        assertThat(profile(override).validate(surface, environment))
            .isEqualTo(UiGestureProblem.MissingActions(setOf(coverMenu, titleMenu)))
        val repaired = override.assign(UiGestureBinding(coverHold, coverMenu))
            .assign(UiGestureBinding(slot(play, UiBuiltInGesture.TAP), titleMenu))
        assertThat(profile(repaired).resolveFor(surface, environment, cover.members.single()).map { it.action })
            .containsExactly(coverMenu)
        assertThat(profile(repaired).validate(surface, environment)).isNull()
    }

    @Test
    fun `recordings indistinguishable on one component can be used on separate components`() {
        val right = builtInRight(cover)
        val stock = environment.copy(stock = environment.stock + UiGestureBinding(right, pause))
        val line = checkNotNull(UiGesturePreset.record("line", "Line", listOf(UiGesturePoint(0f, 0f), UiGesturePoint(90f, 0f))))
        val duplicate = line.copy(id = "another-line")
        val presets = UiGestureProfile(presets = listOf(line, duplicate))
        val custom = UiGestureSlot(cover, UiGesture.custom(line.id))
        val competing = UiSurfaceGestures().assign(UiGestureBinding(custom, coverMenu))
        assertThat(presets.editSurface(surface, competing).validate(surface, stock)).isNotNull
        val remap = competing.unbind(right).assign(UiGestureBinding(slot(play, UiBuiltInGesture.TAP), pause))
        assertThat(presets.editSurface(surface, remap).validate(surface, stock)).isNull()
        assertThat(presets.editSurface(surface,
            remap.assign(UiGestureBinding(UiGestureSlot(cover, UiGesture.custom(duplicate.id)), titleMenu)),
        ).validate(surface, stock)).isNotNull
        assertThat(presets.editSurface(surface,
            remap.assign(UiGestureBinding(UiGestureSlot(play, UiGesture.custom(duplicate.id)), titleMenu)),
        ).validate(surface, stock)).isNull()
    }

    @Test
    fun `motion keyframes interpolate every channel and enforce bounded finite values`() {
        val first = UiGestureTransform()
        val last = UiGestureTransform(translationX = -1f, translationY = 0.5f, scale = 0.8f,
            rotation = 20f, alpha = 0.6f, pivotX = 0f, pivotY = 1f)
        val timeline = UiGestureTimeline(cover, listOf(UiGestureKeyframe(0f, first), UiGestureKeyframe(1f, last)))
        assertThat(timeline.isValid).isTrue()
        assertThat(timeline.at(0.5f)).isEqualTo(UiGestureTransform(-0.5f, 0.25f, 0.9f, 10f, 0.8f, 0.25f, 0.75f))
        assertThat(timeline.at(-10f)).isEqualTo(first)
        assertThat(timeline.at(10f)).isEqualTo(last)
        assertThat(timeline.copy(keyframes = timeline.keyframes.reversed()).isValid).isFalse()
        assertThat(timeline.copy(keyframes = listOf(UiGestureKeyframe(0f, first),
            UiGestureKeyframe(1f, last.copy(alpha = Float.NaN)))).isValid).isFalse()
        val presets = UiGestureMotionPreset.entries.map { it.timeline(cover) }
        assertThat(presets).allMatch { it.isValid }
        assertThat(presets.map { it.at(0.75f) }).doesNotHaveDuplicates()
        assertThat(UiGestureMotionPreset.NONE.timeline(cover).at(0.75f)).isEqualTo(first)
    }

    private fun profile(settings: UiSurfaceGestures) = UiGestureProfile().editSurface(surface, settings)

    private fun slot(target: UiGestureTarget, gesture: UiBuiltInGesture) = UiGestureSlot(target, UiGesture.builtIn(gesture))

    private fun builtInRight(target: UiGestureTarget) = slot(target, UiBuiltInGesture.SWIPE_RIGHT)
}
