package io.github.lorddetson.piplayer.ui.settings

import android.app.Application
import android.content.Context
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.sqlite.SQLiteException
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.library.rules.FolderRule
import io.github.lorddetson.piplayer.library.rules.FolderRuleMatcher
import io.github.lorddetson.piplayer.library.rules.FolderRuleState
import io.github.lorddetson.piplayer.userdata.FolderRuleEntity
import io.github.lorddetson.piplayer.userdata.UserData
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** A row of the Library folders tree: a volume's root at depth 0, its folders below it. */
data class FolderRow(
    val volume: String,
    val name: String,
    val relativePath: String,
    val depth: Int,
    /** The user's own rule for this folder, if any. */
    val rule: FolderRuleEntity?,
    /** What decides about the folder: its own rule or the deepest one above it. */
    val state: FolderRuleState,
)

/** A rule that matches no folder shown: its volume is not attached, or its folder holds no audio now. */
data class RuleNotFound(val rule: FolderRuleEntity, val volumeLabel: String)

/** The Library folders screen: the trees of the attached volumes and the rules that match none of their folders. */
data class FoldersState(val rows: List<FolderRow>, val notFound: List<RuleNotFound>) {

    companion object {
        /**
         * [folders] are null while the library cannot say which folders hold audio, before a sync has succeeded or
         * without access; then no rule counts as not found. [volumes] are the attached volumes with their labels, in the order to
         * show them.
         */
        fun of(folders: List<FolderKey>?, volumes: Map<String, String>, rules: List<FolderRuleEntity>): FoldersState {
            val matcher = FolderRuleMatcher(rules.map { FolderRule(it.volumeName, it.relativePath, it.mode == FolderRuleEntity.INCLUDE) })
            val byPath = rules.associateBy { it.volumeName to it.relativePath.lowercase(Locale.ROOT) }
            fun row(volume: String, name: String, path: String, depth: Int) = FolderRow(
                volume, name, path, depth, byPath[volume to path.lowercase(Locale.ROOT)], matcher.stateFor(volume, path),
            )
            val rows = mutableListOf<FolderRow>()
            fun add(volume: String, nodes: List<FolderNode>, depth: Int) {
                for (node in nodes) {
                    rows += row(volume, node.name, node.relativePath, depth)
                    add(volume, node.children, depth + 1)
                }
            }
            if (folders == null) return FoldersState(emptyList(), emptyList())
            val pathsByVolume = folders.groupBy({ it.volume }, { it.relativePath })
            for ((volume, label) in volumes) {
                val paths = pathsByVolume[volume] ?: continue
                rows += row(volume, label, "", 0)
                add(volume, FolderTree.of(paths), 1)
            }
            val shown = rows.mapNotNullTo(HashSet()) { it.rule }
            return FoldersState(rows, rules.filter { it !in shown }.map { RuleNotFound(it, volumes[it.volumeName] ?: it.volumeName) })
        }
    }
}

/** The Library folders screen's state (docs/library.md §3.6); a change of a rule asks the library for a sync (§3.4). */
class FoldersViewModel(application: Application) : AndroidViewModel(application) {

    private val rules = UserData.folderRules(application)

    val state: StateFlow<FoldersState?> = combine(
        Library.status(application).map { it.known to it.revision }.distinctUntilChanged()
            .map { (known, _) -> if (known) folders(application) else null },
        UserData.database(application).user().folderRulesFlow(),
    ) { folders, rules -> FoldersState.of(folders, attachedVolumes(application), rules) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Gives [row] its own rule; [FolderRuleState.NONE] lets it follow the folder above again. */
    fun choose(row: FolderRow, state: FolderRuleState) {
        val path = row.rule?.relativePath ?: row.relativePath
        write {
            when (state) {
                FolderRuleState.NONE -> if (row.rule != null) rules.remove(row.volume, path)
                FolderRuleState.INCLUDE -> rules.set(row.volume, path, include = true)
                FolderRuleState.EXCLUDE -> rules.set(row.volume, path, include = false)
            }
        }
    }

    fun remove(rule: FolderRuleEntity) = write { rules.remove(rule.volumeName, rule.relativePath) }

    /** A write that fails, as on a full disk, is logged and leaves the rules as they were. */
    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: SQLiteException) {
                Log.w(TAG, "A folder rule write failed", e)
            }
        }
    }

    private fun folders(context: Context): List<FolderKey>? =
        if (Library.mayRead(context)) Library.read(context) { runBlocking { it.library().folders() } } else null

    private companion object {
        const val TAG = "FoldersViewModel"
    }

    /** The attached volumes by their index name, the primary one first, each with the label the system gives it. */
    private fun attachedVolumes(context: Context): Map<String, String> {
        val labels = context.getSystemService(StorageManager::class.java).storageVolumes
            .mapNotNull { volume -> volume.mediaStoreVolumeName?.let { it to volume.getDescription(context) } }
            .toMap()
        return MediaStore.getExternalVolumeNames(context)
            .sortedWith(compareBy<String> { it != MediaStore.VOLUME_EXTERNAL_PRIMARY }.thenBy { labels[it] ?: it })
            .associateWith { labels[it] ?: it }
    }
}
