package io.github.lorddetson.piplayer.ui.identification

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.identification.caa.CaaImage
import io.github.lorddetson.piplayer.identification.musicbrainz.ReleaseDto
import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.identification.musicbrainz.allTracks
import io.github.lorddetson.piplayer.ui.settings.protectedFieldLabel
import java.text.NumberFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The panel of review screen B (TI §6, step 20) shown in place of the card, with its title and Close. */
@Composable
internal fun ReviewPanel(panel: Panel, model: ReviewModel, modifier: Modifier) {
    val title = stringResource(
        when (panel) {
            is Panel.Editions -> R.string.review_choose_edition
            is Panel.Search -> R.string.review_search_manually
            is Panel.Covers -> R.string.review_choose_cover
            is Panel.Editor -> R.string.review_edit_fields
        },
    )
    Column(modifier.fillMaxSize().testTag(ReviewTags.PANEL)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClick = { model.closePanel() }) { Text(stringResource(R.string.review_close)) }
        }
        when (panel) {
            is Panel.Editions -> EditionsPanel(panel, model::chooseEdition)
            is Panel.Search -> SearchPanel(panel, model)
            is Panel.Covers -> CoversPanel(panel, model)
            is Panel.Editor -> EditorPanel(panel, model)
        }
    }
}

private val PANEL_PADDING = 16.dp
private const val THUMBNAIL_DP = 64

/** Choose edition (TI §4.2 step 6): the group's editions, the suggested one first. */
@Composable
private fun EditionsPanel(panel: Panel.Editions, onChoose: (Int) -> Unit) {
    val list = panel.list
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(PANEL_PADDING)) {
        when {
            panel.failed -> item { Note(stringResource(R.string.review_editions_failed), error = true) }
            list == null -> item { CircularProgressIndicator() }
            else -> {
                if (list.capped) item { Note(stringResource(R.string.review_editions_partial, list.editions.size, list.totalCount)) }
                if (panel.openFailed) item { Note(stringResource(R.string.review_edition_open_failed), error = true) }
                itemsIndexed(list.editions) { index, edition ->
                    val without = edition.release.id in panel.withoutSong
                    val chosen = edition.release.id == panel.chosen
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ReviewTags.edition(index))
                            .selectable(selected = chosen, enabled = !without && panel.opening == null, role = Role.Button) { onChoose(index) }
                            .padding(vertical = 8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(edition.release.title, style = MaterialTheme.typography.bodyLarge)
                            Text(editionFacts(edition.release), style = MaterialTheme.typography.bodyMedium)
                            val notes = listOfNotNull(
                                stringResource(R.string.review_suggested_edition).takeIf { edition.suggested },
                                stringResource(R.string.review_chosen_edition).takeIf { chosen },
                                stringResource(R.string.review_without_song).takeIf { without },
                            )
                            for (note in notes) {
                                Text(
                                    note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (without) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        if (panel.opening == index) CircularProgressIndicator(Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

/** An edition's type, format, countries, date, first label and number of tracks (TI §4.2 step 6). */
@Composable
private fun editionFacts(release: ReleaseDto): String {
    val group = release.releaseGroup
    val type = group?.let { (listOfNotNull(it.primaryType) + it.secondaryTypes).joinToString(" + ").ifEmpty { null } }
    val countries = release.releaseEvents.flatMap { it.area?.isoCodes.orEmpty() }.distinct().ifEmpty { listOfNotNull(release.country) }
    val tracks = release.media.sumOf { it.trackCount ?: it.allTracks.size }.takeIf { it > 0 }
    return listOfNotNull(
        type,
        release.media.firstOrNull()?.format,
        countries.joinToString(", ").ifEmpty { null },
        release.date?.takeIf { it.isNotBlank() },
        release.labelInfo.firstNotNullOfOrNull { it.label?.name?.takeIf(String::isNotBlank) },
        tracks?.let { pluralStringResource(R.plurals.review_track_count, it, it) },
    ).joinToString(" · ")
}

/** Search manually (TI §3.3): the text, Search, what it said and the results with their confidence. */
@Composable
private fun SearchPanel(panel: Panel.Search, model: ReviewModel) {
    val percent = NumberFormat.getPercentInstance(currentLocale())
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(PANEL_PADDING)) {
        item {
            OutlinedTextField(
                value = panel.text,
                onValueChange = model::searchText,
                label = { Text(stringResource(R.string.review_search_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { model.search() }),
                modifier = Modifier.fillMaxWidth().testTag(ReviewTags.SEARCH_FIELD),
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                Button(onClick = model::search, enabled = !panel.searching && panel.choosing == null) { Text(stringResource(R.string.review_search)) }
                if (panel.searching) CircularProgressIndicator(Modifier.size(20.dp))
            }
        }
        panel.note?.let { note ->
            item {
                Note(
                    stringResource(
                        when (note) {
                            SearchNote.REFUSED -> R.string.review_search_refused
                            SearchNote.NOTHING_FOUND -> R.string.review_search_nothing
                            SearchNote.NOT_FOUND -> R.string.review_search_not_found
                            SearchNote.FAILED -> R.string.review_search_failed
                            SearchNote.EDITION_FAILED -> R.string.review_edition_failed
                        },
                    ),
                    error = note != SearchNote.NOTHING_FOUND,
                )
            }
        }
        itemsIndexed(panel.results.orEmpty()) { index, result ->
            val match = result.match
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ReviewTags.result(index))
                    .clickable(enabled = panel.choosing == null, role = Role.Button) { model.chooseResult(index) }
                    .padding(vertical = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(match.candidate.title, style = MaterialTheme.typography.bodyLarge)
                    if (match.candidate.artistCredit.isNotEmpty()) Text(match.candidate.artistCredit, style = MaterialTheme.typography.bodyMedium)
                    val release = result.release
                    val at = result.at
                    if (release != null && at != null) Text("${at.disc}-${at.position} · ${release.title}", style = MaterialTheme.typography.bodySmall)
                }
                if (panel.choosing == index) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                } else {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(percent.format(match.score), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(bandName(ManualResults.band(match.score))), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Choose cover (TI §5.2): the release's images, then the file's embedded pictures. */
@Composable
private fun CoversPanel(panel: Panel.Covers, model: ReviewModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(PANEL_PADDING)) {
        section(R.string.review_covers_release)
        val images = panel.images
        when {
            images == null -> item { CircularProgressIndicator() }
            else -> {
                panel.indexNote?.let { note ->
                    item {
                        Note(
                            stringResource(if (note == CoversNote.NO_IMAGES) R.string.review_covers_no_images else R.string.review_covers_failed),
                            error = note == CoversNote.FAILED,
                        )
                    }
                }
                itemsIndexed(images) { index, image ->
                    CoverRow(
                        tag = ReviewTags.coverImage(index),
                        enabled = panel.choosing == null,
                        loading = panel.choosing == index,
                        picture = { CaaThumbnail(image, model) },
                        text = (image.types.joinToString(", ") + image.comment.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()).ifEmpty { "-" },
                    ) { model.chooseImage(index) }
                }
            }
        }
        if (panel.failed) item { Note(stringResource(R.string.review_cover_image_failed), error = true) }
        section(R.string.review_covers_embedded)
        val embedded = panel.embedded
        when {
            embedded == null -> item { CircularProgressIndicator() }
            panel.embeddedFailed -> item { Note(stringResource(R.string.review_covers_embedded_failed), error = true) }
            embedded.isEmpty() -> item { Note(stringResource(R.string.review_covers_no_embedded)) }
            else -> itemsIndexed(embedded) { index, picture ->
                val size = picture.size
                CoverRow(
                    tag = ReviewTags.embeddedImage(index),
                    enabled = panel.choosing == null,
                    loading = false,
                    picture = { BytesThumbnail(picture.bytes) },
                    text = size?.let { stringResource(R.string.review_pixels, it.width, it.height) } ?: "-",
                ) { model.chooseEmbedded(index) }
            }
        }
    }
}

private fun LazyListScope.section(title: Int) = item {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun CoverRow(tag: String, enabled: Boolean, loading: Boolean, picture: @Composable () -> Unit, text: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Box(Modifier.size(THUMBNAIL_DP.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) { picture() }
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (loading) CircularProgressIndicator(Modifier.size(20.dp))
    }
}

/** A release image's 250 px thumbnail, fetched once through the model; nothing without one. */
@Composable
private fun CaaThumbnail(image: CaaImage, model: ReviewModel) {
    val px = with(LocalDensity.current) { THUMBNAIL_DP.dp.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, image) {
        val bytes = model.thumbnail(image)?.bytes ?: return@produceState
        value = withContext(Dispatchers.Default) { decoded(bytes, px) }
    }
    bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
}

@Composable
private fun BytesThumbnail(bytes: ByteArray) {
    val px = with(LocalDensity.current) { THUMBNAIL_DP.dp.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, bytes) { value = withContext(Dispatchers.Default) { decoded(bytes, px) } }
    bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
}

/** Edit fields: a text field per field the review can propose, a number with its total, and Save. */
@Composable
private fun EditorPanel(panel: Panel.Editor, model: ReviewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PANEL_PADDING),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (entry in panel.entries) {
            val field = entry.field
            val label = stringResource(protectedFieldLabel(field))
            val invalid = field in panel.invalid
            val help = when {
                invalid -> stringResource(R.string.review_editor_digits)
                FieldEdits.isList(field) -> stringResource(R.string.review_editor_list)
                else -> null
            }
            if (FieldEdits.hasTotal(field)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditorField(entry.text, label, invalid, help, ReviewTags.editorField(field), number = true, Modifier.weight(1f)) {
                        model.editEntry(entry.copy(text = it))
                    }
                    EditorField(entry.total, stringResource(R.string.review_editor_total), invalid, null, ReviewTags.editorTotal(field), number = true, Modifier.weight(1f)) {
                        model.editEntry(entry.copy(total = it))
                    }
                }
            } else {
                EditorField(entry.text, label, invalid = false, help, ReviewTags.editorField(field), number = false, Modifier.fillMaxWidth()) {
                    model.editEntry(entry.copy(text = it))
                }
            }
        }
        Button(onClick = model::saveEdits, enabled = panel.invalid.isEmpty()) { Text(stringResource(R.string.review_save)) }
    }
}

@Composable
private fun EditorField(
    value: String,
    label: String,
    invalid: Boolean,
    help: String?,
    tag: String,
    number: Boolean,
    modifier: Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = invalid,
        supportingText = help?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
        modifier = modifier.testTag(tag),
    )
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/** The name of a confidence band as text, which the band's color never stands for alone (TI §6). */
internal fun bandName(band: Band): Int = when (band) {
    Band.HIGH -> R.string.review_band_high
    Band.MEDIUM -> R.string.review_band_medium
    Band.NO_MATCH -> R.string.review_band_low
}
