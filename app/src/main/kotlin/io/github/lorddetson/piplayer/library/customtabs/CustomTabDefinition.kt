package io.github.lorddetson.piplayer.library.customtabs

import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.library.lists.ListKind
import java.util.UUID

private const val CUSTOM_TAB_PREFIX = "custom-"
private const val TAB_COMPONENT_PREFIX = "tab:"
private const val MAX_TAB_NAME_LENGTH = 80
internal const val MAX_TAB_HINT_LENGTH = 512

internal fun isCustomTabId(id: String): Boolean =
    id.startsWith(CUSTOM_TAB_PREFIX) && isCanonicalUuid(id.removePrefix(CUSTOM_TAB_PREFIX))

internal fun customTabIdForComponent(id: String): String? =
    id.takeIf { it.startsWith(TAB_COMPONENT_PREFIX) }?.removePrefix(TAB_COMPONENT_PREFIX)?.takeIf(::isCustomTabId)

internal fun isCanonicalUuid(value: String): Boolean =
    value.length == 36 && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

internal fun newCustomTabId(): String = "$CUSTOM_TAB_PREFIX${UUID.randomUUID()}"

/** The portable template. A structural source needs an explicit installation-local attachment. */
internal data class CustomTabDefinition(val id: String, val name: String, val source: CustomTabTemplateSource) {
    val componentId: String get() = "$TAB_COMPONENT_PREFIX$id"
    val isValid: Boolean get() = isValidId(id) && validLabel(name, MAX_TAB_NAME_LENGTH) && source.isValid

    companion object {
        fun isValidId(id: String) = isCustomTabId(id)

        fun isValidName(name: String) = validLabel(name.trim(), MAX_TAB_NAME_LENGTH)
    }
}

internal sealed interface CustomTabTemplateSource {
    data class Playlist(val sourceRef: String, val hint: String) : CustomTabTemplateSource
    data class Folder(val sourceRef: String, val hint: String) : CustomTabTemplateSource
    data class Collection(val kind: ListKind) : CustomTabTemplateSource
    data class Rules(val rules: TabTagRuleSet) : CustomTabTemplateSource

    val isValid: Boolean get() = when (this) {
        is Playlist -> isCanonicalUuid(sourceRef) && validLabel(hint, MAX_TAB_HINT_LENGTH)
        is Folder -> isCanonicalUuid(sourceRef) && validLabel(hint, MAX_TAB_HINT_LENGTH)
        is Collection -> kind.smart
        is Rules -> true
    }
}

/** A user explicitly selected a source in the local library, or authored a portable source. */
internal sealed interface CustomTabSourceDraft {
    data class Playlist(val id: Long, val createdAt: Long, val hint: String) : CustomTabSourceDraft
    data class Folder(val key: FolderKey, val hint: String) : CustomTabSourceDraft
    data class Collection(val kind: ListKind) : CustomTabSourceDraft
    data class Rules(val rules: TabTagRuleSet) : CustomTabSourceDraft
}

/** Runtime still checks whether the selected local object exists and has the stored identity. */
internal data class CustomTabRecord(
    val definition: CustomTabDefinition,
    val resolvedSource: CustomTabSource?,
    val playlistCreatedAt: Long? = null,
)

private fun validLabel(text: String, limit: Int): Boolean =
    text.length in 1..limit && text.isNotBlank() && text.none(Char::isISOControl) && Charsets.UTF_8.newEncoder().canEncode(text)
