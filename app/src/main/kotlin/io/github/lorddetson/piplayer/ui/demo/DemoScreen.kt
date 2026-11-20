package io.github.lorddetson.piplayer.ui.demo

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.demo.DemoAudio
import io.github.lorddetson.piplayer.demo.DemoTagLesson
import io.github.lorddetson.piplayer.settings.ThemeMode
import io.github.lorddetson.piplayer.ui.player.NowPlaying
import io.github.lorddetson.piplayer.ui.settings.label
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DemoScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val artist = stringResource(R.string.demo_artist)
    val album = stringResource(R.string.demo_album)
    val unknownTitle = stringResource(R.string.demo_unknown_file)
    val morningTitle = stringResource(R.string.demo_morning_steps)
    val quietTitle = stringResource(R.string.demo_quiet_orbit)
    var lesson by rememberSaveable { mutableStateOf(DemoTagLesson.ORIGINAL) }
    var theme by rememberSaveable { mutableStateOf(ThemeMode.GRAPHITE) }
    var showingPlayer by remember { mutableStateOf(false) }
    var preparedFiles by remember { mutableStateOf<List<File>?>(null) }
    val player = remember(context) {
        val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
        ExoPlayer.Builder(context).setAudioAttributes(attributes, true).setHandleAudioBecomingNoisy(true).build()
    }

    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }

    LaunchedEffect(context) {
        preparedFiles = try {
            withContext(Dispatchers.IO) { prepareSamples(File(context.cacheDir, "demo-audio-v1")) }
        } catch (_: IOException) {
            emptyList()
        }
    }

    val applied = lesson == DemoTagLesson.APPLIED
    val displayedTitle = if (applied) morningTitle else unknownTitle
    val morning = if (applied) metadata(displayedTitle, artist, album) else metadata(displayedTitle)

    val back = {
        player.pause()
        if (showingPlayer) showingPlayer = false else onBack()
    }
    BackHandler(onBack = back)

    PiPlayerTheme(mode = theme) {
        if (showingPlayer) {
            NowPlaying(player = player, cover = null, onClose = back)
        } else {
            Scaffold(
                modifier = Modifier.fillMaxSize().testTag("demo-screen"),
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.demo_title)) },
                        navigationIcon = {
                            IconButton(onClick = back, modifier = Modifier.testTag("demo-exit")) {
                                Icon(painterResource(R.drawable.ic_back), stringResource(R.string.settings_back))
                            }
                        },
                    )
                },
            ) { padding ->
                Column(
                    modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(stringResource(R.string.demo_music), style = MaterialTheme.typography.titleLarge)
                    val files = preparedFiles
                    when {
                        files == null -> Text(stringResource(R.string.demo_audio_preparing))
                        files.isEmpty() -> Text(stringResource(R.string.demo_audio_error), color = MaterialTheme.colorScheme.error)
                        else -> DemoAudio.samples.forEachIndexed { index, sample ->
                            val title = if (sample == DemoAudio.Sample.MORNING_STEPS) displayedTitle else quietTitle
                            Button(
                                modifier = Modifier.fillMaxWidth().testTag("demo-play-${sample.id}"),
                                onClick = {
                                    val info = if (sample == DemoAudio.Sample.MORNING_STEPS) morning
                                    else metadata(quietTitle, artist, album)
                                    val item = MediaItem.Builder().setMediaId(sample.id).setUri(Uri.fromFile(files[index]))
                                        .setMediaMetadata(info).build()
                                    player.setMediaItem(item)
                                    player.prepare()
                                    player.play()
                                    showingPlayer = true
                                },
                            ) { Text(title) }
                        }
                    }
                    Text(stringResource(R.string.demo_theme), style = MaterialTheme.typography.titleLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (mode in listOf(ThemeMode.GRAPHITE, ThemeMode.AMOLED, ThemeMode.WARM_LIGHT)) {
                            FilterChip(
                                selected = theme == mode,
                                onClick = { theme = mode },
                                label = { Text(stringResource(mode.label)) },
                                modifier = Modifier.testTag("demo-theme-${mode.stored}"),
                            )
                        }
                    }
                    DemoTagCard(lesson) { lesson = it }
                }
            }
        }
    }
}

@Composable
private fun DemoTagCard(lesson: DemoTagLesson, change: (DemoTagLesson) -> Unit) {
    ElevatedCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.demo_tag_title), style = MaterialTheme.typography.titleLarge)
            when (lesson) {
                DemoTagLesson.ORIGINAL -> {
                    Text(stringResource(R.string.demo_unknown_file))
                    TextButton(onClick = { change(lesson.preview()) }, modifier = Modifier.testTag("demo-tag-preview")) {
                        Text(stringResource(R.string.demo_tag_candidate))
                    }
                }
                DemoTagLesson.PREVIEW -> {
                    Text(
                        stringResource(R.string.demo_tag_example, stringResource(R.string.demo_unknown_file)),
                        modifier = Modifier.testTag("demo-tag-example"),
                    )
                    Text(stringResource(R.string.demo_tag_explained))
                    Text(stringResource(R.string.demo_morning_steps), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.demo_artist))
                    Text(stringResource(R.string.demo_album))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { change(lesson.apply()) }, modifier = Modifier.testTag("demo-tag-apply")) {
                            Text(stringResource(R.string.demo_tag_apply))
                        }
                        TextButton(onClick = { change(lesson.cancel()) }) {
                            Text(stringResource(R.string.settings_dialog_cancel))
                        }
                    }
                }
                DemoTagLesson.APPLIED -> {
                    Text(stringResource(R.string.demo_morning_steps), modifier = Modifier.testTag("demo-tag-applied"))
                    TextButton(onClick = { change(lesson.undo()) }, modifier = Modifier.testTag("demo-tag-undo")) {
                        Text(stringResource(R.string.demo_tag_undo))
                    }
                }
            }
        }
    }
}

private fun metadata(title: String, artist: String? = null, album: String? = null): MediaMetadata =
    MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).build()

private fun prepareSamples(directory: File): List<File> {
    Files.createDirectories(directory.toPath())
    return DemoAudio.samples.map { sample ->
        val file = File(directory, "${sample.id}.wav")
        if (file.length() <= 44) {
            val temp = File.createTempFile(sample.id, ".tmp", directory)
            try {
                temp.outputStream().buffered().use { DemoAudio.writeSample(sample, it) }
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                temp.delete()
            }
        }
        file
    }
}
