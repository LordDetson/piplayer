package io.github.lorddetson.piplayer.settings

import kotlinx.serialization.Serializable

private val presetIdPattern = Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}")
private val scopePattern = Regex("[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,79}")
private const val MAX_PRESETS = 24
private const val MAX_ASSIGNMENTS_PER_SURFACE = 96
private const val MAX_TIMELINES = 12

internal fun validGesturePresetId(id: String): Boolean = presetIdPattern.matches(id)

@Serializable
internal enum class UiBuiltInGesture {
    SWIPE_LEFT, SWIPE_RIGHT, SWIPE_UP, SWIPE_DOWN,
    CIRCLE_CLOCKWISE, CIRCLE_COUNTERCLOCKWISE,
    TAP, DOUBLE_TAP, TRIPLE_TAP, LONG_PRESS, TAP_THEN_LONG_PRESS, DOUBLE_TAP_THEN_LONG_PRESS,
}

@Serializable
internal data class UiGesture(val builtIn: UiBuiltInGesture? = null, val presetId: String? = null) {
    val isValid: Boolean get() = if (builtIn == null) presetId?.let(::validGesturePresetId) == true else presetId == null

    companion object {
        fun builtIn(id: UiBuiltInGesture) = UiGesture(builtIn = id)
        fun custom(id: String) = UiGesture(presetId = id)
    }
}

@Serializable
@ConsistentCopyVisibility
internal data class UiGestureTarget private constructor(val members: List<String>) {
    val isGroup: Boolean get() = members.size > 1
    val isValid: Boolean get() = members.size in 1..UI_CANVAS_MAX_ITEMS && members.all(::validComponentId) &&
        members.zipWithNext().all { (first, second) -> first < second }

    companion object {
        fun component(id: String) = UiGestureTarget(listOf(id))
        fun group(ids: Collection<String>) = UiGestureTarget(ids.sorted())
    }
}

@Serializable
internal data class UiGestureSlot(val target: UiGestureTarget, val gesture: UiGesture) {
    val isValid: Boolean get() = target.isValid && gesture.isValid
}

@Serializable
internal enum class UiGestureActionId {
    STOCK_CLICK, CONTEXT_MENU, NEXT_TRACK, PREVIOUS_TRACK, OPEN_QUEUE, PLAY_PAUSE,
    VOLUME_UP, VOLUME_DOWN, SEEK_FORWARD, SEEK_BACKWARD,
    SPEED_UP, SPEED_DOWN, SPEED_RESET, TOGGLE_FAVORITE, CYCLE_REPEAT, TOGGLE_SHUFFLE, SLEEP_TIMER,
}

@Serializable
internal data class UiGestureAction(
    val id: UiGestureActionId,
    val scope: String? = null,
    val targetId: String? = null,
    val amountSeconds: Int? = null,
) {
    val isValid: Boolean get() = (scope == null || scopePattern.matches(scope)) &&
        (targetId == null || validComponentId(targetId)) &&
        ((id != UiGestureActionId.STOCK_CLICK && id != UiGestureActionId.CONTEXT_MENU) || targetId != null) &&
        (amountSeconds == null || id in seekActions && amountSeconds in 1..300)

    private companion object {
        val seekActions = setOf(UiGestureActionId.SEEK_FORWARD, UiGestureActionId.SEEK_BACKWARD)
    }
}

@Serializable
internal data class UiGestureBinding(
    val slot: UiGestureSlot,
    val action: UiGestureAction,
    val timelines: List<UiGestureTimeline> = emptyList(),
) {
    val isValid: Boolean get() {
        if (!slot.isValid || !action.isValid || timelines.size > MAX_TIMELINES || !timelines.all(UiGestureTimeline::isValid)) {
            return false
        }
        val animated = timelines.flatMap { it.target.members }
        return animated.size == animated.toSet().size
    }
}

@Serializable
internal data class UiSurfaceGestures(
    val bindings: List<UiGestureBinding> = emptyList(),
    val unbound: Set<UiGestureSlot> = emptySet(),
) {
    val isEmpty: Boolean get() = bindings.isEmpty() && unbound.isEmpty()
    val slots: List<UiGestureSlot> get() = bindings.map(UiGestureBinding::slot) + unbound

    val isValid: Boolean get() {
        val owned = slots
        return owned.size <= MAX_ASSIGNMENTS_PER_SURFACE && owned.size == owned.toSet().size &&
            bindings.all(UiGestureBinding::isValid) && unbound.all(UiGestureSlot::isValid)
    }

    fun assign(binding: UiGestureBinding) = copy(
        bindings = bindings.filterNot { it.slot == binding.slot } + binding,
        unbound = unbound - binding.slot,
    )

    fun unbind(slot: UiGestureSlot) = restore(slot).copy(unbound = unbound + slot)

    fun restore(slot: UiGestureSlot) = copy(bindings = bindings.filterNot { it.slot == slot }, unbound = unbound - slot)
}

internal data class UiGestureEnvironment(
    val components: Set<String>,
    val groups: List<Set<String>>,
    val stock: List<UiGestureBinding>,
) {
    fun reaches(target: UiGestureTarget): Boolean = target.members.isNotEmpty() && components.containsAll(target.members) &&
        (!target.isGroup || groups.any { it.size == target.members.size && it.containsAll(target.members) })
}

internal sealed interface UiGestureProblem {
    data object InvalidProfile : UiGestureProblem
    data class MissingActions(val actions: Set<UiGestureAction>) : UiGestureProblem
}

@Serializable
internal data class UiGestureProfile(
    val presets: List<UiGesturePreset> = emptyList(),
    val surfaces: Map<UiSurface, UiSurfaceGestures> = emptyMap(),
) {
    val isEmpty: Boolean get() = presets.isEmpty() && surfaces.values.all(UiSurfaceGestures::isEmpty)
    val isValid: Boolean get() {
        val ids = presets.map(UiGesturePreset::id).toSet()
        return presets.size <= MAX_PRESETS && ids.size == presets.size && presets.all(UiGesturePreset::isValid) &&
            surfaces.values.all { gestures ->
                !gestures.isEmpty && gestures.isValid && gestures.slots.all { slot ->
                    slot.gesture.presetId?.let { it in ids } != false
                }
            }
    }

    fun editSurface(surface: UiSurface, gestures: UiSurfaceGestures) = copy(
        surfaces = if (gestures.isEmpty) surfaces - surface else surfaces + (surface to gestures),
    )

    /** Every assignment that can win on at least one reachable component. */
    fun resolve(surface: UiSurface, environment: UiGestureEnvironment): List<UiGestureBinding> {
        val candidates = candidates(surface, environment)
        val winning = environment.components.flatMap { choices(candidates, it) }.mapTo(mutableSetOf(), UiGestureBinding::slot)
        return candidates.filter { it.slot in winning }
    }

    fun resolveFor(surface: UiSurface, environment: UiGestureEnvironment, componentId: String): List<UiGestureBinding> =
        choices(candidates(surface, environment), componentId)

    fun validate(surface: UiSurface, environment: UiGestureEnvironment, previous: UiGestureProfile = this): UiGestureProblem? {
        if (!isValid) return UiGestureProblem.InvalidProfile
        val candidates = candidates(surface, environment)
        val byComponent = environment.components.map { choices(candidates, it) }
        if (presets.isNotEmpty() && byComponent.any(::hasCompetingPaths)) return UiGestureProblem.InvalidProfile

        val overrides = surfaces[surface]
        val previousOverrides = previous.surfaces[surface]
        val available = byComponent.flatten().mapTo(mutableSetOf(), UiGestureBinding::action)
        val missing = environment.stock.asSequence().filter { stock ->
            stock.action !in available && (overrides.displaces(stock) || previousOverrides.displaces(stock))
        }.map(UiGestureBinding::action).toSet()
        return if (missing.isEmpty()) null else UiGestureProblem.MissingActions(missing)
    }

    /** Expire removed components and groups without retargeting an assignment to a different membership. */
    fun prune(surface: UiSurface, environment: UiGestureEnvironment, previous: UiGestureProfile = this): UiGestureProfile {
        val current = surfaces[surface] ?: UiSurfaceGestures()
        val previousOverrides = previous.surfaces[surface]
        // Keep suppressions for hidden stock so later edits still protect their alternate bindings.
        val hiddenStock = environment.stock.filter { stock ->
            !environment.reaches(stock.slot.target) && (current.displaces(stock) || previousOverrides.displaces(stock))
        }.map(UiGestureBinding::slot)
        return editSurface(surface, current.copy(
            bindings = current.bindings.reachableBindings(environment),
            unbound = current.unbound.filterTo(mutableSetOf()) { environment.reaches(it.target) } + hiddenStock,
        ))
    }

    private fun candidates(surface: UiSurface, environment: UiGestureEnvironment): List<UiGestureBinding> {
        val current = surfaces[surface] ?: UiSurfaceGestures()
        val replaced = current.bindings.map(UiGestureBinding::slot).toSet() + current.unbound
        val custom = current.bindings.reachableBindings(environment)
        val stock = environment.stock.filterNot { it.slot in replaced }.reachableBindings(environment)
        return custom.sortedBy { it.slot.target.isGroup } + stock.sortedBy { it.slot.target.isGroup }
    }

    private fun choices(candidates: List<UiGestureBinding>, componentId: String) = candidates
        .filter { componentId in it.slot.target.members }.distinctBy { it.slot.gesture }

    private fun hasCompetingPaths(bindings: List<UiGestureBinding>): Boolean {
        val gestures = bindings.map { it.slot.gesture }
        return gestures.any { gesture ->
            val preset = presets.firstOrNull { it.id == gesture.presetId } ?: return@any false
            gestures.any { it != gesture && preset.conflictsWith(it, presets) }
        }
    }
}

private fun List<UiGestureBinding>.reachableBindings(environment: UiGestureEnvironment): List<UiGestureBinding> =
    filter { environment.reaches(it.slot.target) }.map { binding ->
        binding.copy(timelines = binding.timelines.filter { environment.reaches(it.target) })
    }

private fun UiSurfaceGestures?.displaces(stock: UiGestureBinding): Boolean {
    if (this == null) return false
    return stock.slot in unbound || bindings.any { binding ->
        binding.action != stock.action && binding.slot.gesture == stock.slot.gesture &&
            binding.slot.target.members.any { it in stock.slot.target.members }
    }
}
