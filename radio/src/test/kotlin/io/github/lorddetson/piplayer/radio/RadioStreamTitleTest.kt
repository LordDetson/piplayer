package io.github.lorddetson.piplayer.radio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RadioStreamTitleTest {
    @Test
    fun `the radio screen excludes local playback`() {
        assertNull(radioStreamTitle("media:15", "Local Album", "Local Song", "Local Artist"))
    }

    @Test
    fun `station fallback is visible without pretending it is a song`() {
        assertEquals(RadioStreamTitle("Pi Jazz", null), radioStreamTitle("radio:13", "Pi Jazz", "Pi Jazz", null))
        assertEquals(RadioStreamTitle("Pi Jazz", null), radioStreamTitle("radio:13", "Pi Jazz", "  ", null))
    }

    @Test
    fun `live metadata updates the song while retaining the selected station`() {
        assertEquals(
            RadioStreamTitle("Pi Jazz", "Evening Light"),
            radioStreamTitle("radio:13", "Pi Jazz", " Evening Light ", null),
        )
        assertEquals(
            RadioStreamTitle("Pi Jazz", "The Quartet - Evening Light"),
            radioStreamTitle("radio:13", "Pi Jazz", "Evening Light", "The Quartet"),
        )
    }
}
