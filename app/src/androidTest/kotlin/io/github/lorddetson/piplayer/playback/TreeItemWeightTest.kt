package io.github.lorddetson.piplayer.playback

import android.media.MediaDescription
import android.media.browse.MediaBrowser
import android.os.Bundle
import android.os.Parcel
import android.util.Log
import androidx.media3.common.MediaMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The weight the tree gives an item is an upper bound of what it takes in a legacy browser's list on this device
 * (docs/playback.md §10.2), measured with the platform's own media item, which Media3's legacy item writes into a parcel
 * the same way, under a longer class name.
 */
@RunWith(AndroidJUnit4::class)
class TreeItemWeightTest {

    /** The bytes of an item as Media3 hands it to a legacy browser: flags and a description with the media type. */
    private fun parcelSize(mediaId: String, title: String?, subtitle: String?, description: String?, iconUri: String? = null): Int {
        val extras = Bundle().apply { putLong(MEDIA_TYPE_KEY, MediaMetadata.MEDIA_TYPE_MUSIC.toLong()) }
        val item = MediaBrowser.MediaItem(
            MediaDescription.Builder().setMediaId(mediaId).setTitle(title).setSubtitle(subtitle).setDescription(description)
                .setIconUri(iconUri?.let(android.net.Uri::parse)).setExtras(extras).build(),
            MediaBrowser.MediaItem.FLAG_PLAYABLE,
        )
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(item, 0)
            return parcel.dataSize() + 2 * (LEGACY_CLASS.length - item.javaClass.name.length)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun theWeightOfAnItemIsAtLeastItsParcel() {
        val ids = listOf("q2/songs?key=&sort=title&rev=0&q=&start=12345&index=678", "artist/ann", "x".repeat(300))
        val texts = listOf(
            Triple("Alpha", "Ann", "First"),
            Triple("Ёжик в тумане", "Ёлкин Бор", "Ночной ёж"),
            Triple("夜に駆ける", "YOASOBI", "THE BOOK"),
            Triple("🎵".repeat(40), null, null),
            Triple("Wide " + "x".repeat(300), "y".repeat(200), "z".repeat(200)),
        )
        for (id in ids) {
            for ((title, subtitle, description) in texts) {
                val real = parcelSize(id, title, subtitle, description)
                val weight = TreeBuckets.weight(id.length, title, subtitle, description)
                Log.i(TAG, "weight $weight, parcel $real: $id / $title")
                assertThat(weight).describedAs("$id / $title").isGreaterThanOrEqualTo(real)
                // Not so much above it that buckets come out needlessly small.
                assertThat(weight - real).describedAs("$id / $title").isLessThan(100)
            }
        }
    }

    @Test
    fun theWeightOfAnItemWithACoverUriIsAtLeastItsParcel() {
        // The cover URIs of docs/library.md §13.8.2, of every kind and length the provider gives.
        val uris = listOf(
            "content://io.github.lorddetson.piplayer.covers/s/1/k3x90",
            "content://io.github.lorddetson.piplayer.covers/s/9999999999/k3x9zzzzzz",
            "content://io.github.lorddetson.piplayer.benchmark.covers/a/123456/k3x91z",
            "content://io.github.lorddetson.piplayer.covers/r/7/k3x90",
        )
        for (uri in uris) {
            for ((id, title) in listOf("artist/ann" to "Ann", "q2/songs?key=&sort=title&rev=0&q=&start=12345&index=678" to "Wide " + "x".repeat(300))) {
                val real = parcelSize(id, title, "Ann", "First", uri)
                val weight = TreeBuckets.weight(id.length, title, "Ann", "First", artworkUriLength = uri.length)
                Log.i(TAG, "weight $weight, parcel $real: $uri / $id")
                assertThat(weight).describedAs("$uri / $id").isGreaterThanOrEqualTo(real)
                assertThat(weight - real).describedAs("$uri / $id").isLessThan(160)
            }
        }
    }

    private companion object {
        const val TAG = "TreeItemWeight"
        const val MEDIA_TYPE_KEY = "androidx.media3.session.EXTRAS_KEY_MEDIA_TYPE_COMPAT"
        const val LEGACY_CLASS = "androidx.media3.session.legacy.MediaBrowserCompat\$MediaItem"
    }
}
