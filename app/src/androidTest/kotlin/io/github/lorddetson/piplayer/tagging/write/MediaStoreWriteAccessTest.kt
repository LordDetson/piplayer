package io.github.lorddetson.piplayer.tagging.write

import android.content.ContentValues
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaStoreWriteAccessTest {
    private val context = TestFiles.targetContext
    private val resolver = context.contentResolver

    @Test
    fun writesAnOwnedMediaStoreItemWithTruncation() {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "piplayer-write-access-fixture.mp3")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
        try {
            resolver.openOutputStream(uri)!!.use { it.write(byteArrayOf(1, 2, 3, 4)) }

            val access = MediaStoreWriteAccess(context)
            assertThat(access.requestAccess(emptyList())).isNull()
            ParcelFileDescriptor.AutoCloseOutputStream(access.openForWrite(uri)).use { it.write(byteArrayOf(5, 6)) }

            assertThat(resolver.openInputStream(uri)!!.use { it.readBytes() }).containsExactly(5, 6)
        } finally {
            resolver.delete(uri, null, null)
        }
    }
}
