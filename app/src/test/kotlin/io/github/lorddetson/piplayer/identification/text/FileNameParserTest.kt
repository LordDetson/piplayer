package io.github.lorddetson.piplayer.identification.text

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Real-world naming patterns with made-up artists and titles. Columns: file name, artist, title, track number; an
 * empty cell means "not in the name".
 */
class FileNameParserTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        nullValues = [""],
        textBlock = """
        The Quiet Harbors - Paper Lanterns.mp3                          | The Quiet Harbors | Paper Lanterns                 |
        01 - The Quiet Harbors - Paper Lanterns.mp3                     | The Quiet Harbors | Paper Lanterns                 | 1
        01. The Quiet Harbors - Paper Lanterns.mp3                      | The Quiet Harbors | Paper Lanterns                 | 1
        The_Quiet_Harbors_-_Paper_Lanterns.mp3                          | The Quiet Harbors | Paper Lanterns                 |
        07. Paper Lanterns.flac                                         |                   | Paper Lanterns                 | 7
        03 Paper Lanterns.m4a                                           |                   | Paper Lanterns                 | 3
        1-03 Paper Lanterns.flac                                        |                   | Paper Lanterns                 | 3
        12 - Paper Lanterns.ogg                                         |                   | Paper Lanterns                 | 12
        Paper Lanterns.mp3                                              |                   | Paper Lanterns                 |
        Mr. Lantern.opus                                                |                   | Mr. Lantern                    |
        99 Red Lanterns.mp3                                             |                   | 99 Red Lanterns                |
        The Quiet Harbors - Paper Lanterns (Night Remix).mp3            | The Quiet Harbors | Paper Lanterns (Night Remix)   |
        The Quiet Harbors - Paper Lanterns (Live).mp3                   | The Quiet Harbors | Paper Lanterns (Live)          |
        The Quiet Harbors - Paper Lanterns [320kbps].mp3                | The Quiet Harbors | Paper Lanterns                 |
        The Quiet Harbors - Paper Lanterns (128 kbps).mp3               | The Quiet Harbors | Paper Lanterns                 |
        The Quiet Harbors - Paper Lanterns (Official Video).mp3         | The Quiet Harbors | Paper Lanterns                 |
        The Quiet Harbors - Paper Lanterns (Official Music Video).mp3   | The Quiet Harbors | Paper Lanterns                 |
        The Quiet Harbors - Paper Lanterns (Lyrics).mp3                 | The Quiet Harbors | Paper Lanterns                 |
        The Quiet Harbors - Paper Lanterns (www.example-music.net).mp3  | The Quiet Harbors | Paper Lanterns                 |
        The Quiet Harbors - Paper Lanterns - example-songs.ru.mp3       | The Quiet Harbors | Paper Lanterns                 |
        The Quiet Harbors - Paper Lanterns (1).mp3                      | The Quiet Harbors | Paper Lanterns                 |
        The Quiet Harbors - Paper Lanterns - Copy.mp3                   | The Quiet Harbors | Paper Lanterns                 |
        The Quiet Harbors feat. Ada Vale - Paper Lanterns.mp3           | The Quiet Harbors feat. Ada Vale | Paper Lanterns  |
        The Quiet Harbors - Harbor Lights - 03 - Paper Lanterns.flac    | The Quiet Harbors | Paper Lanterns                 | 3
        Тихая Гавань - Бумажные фонари.mp3                              | Тихая Гавань      | Бумажные фонари                |
        The Quiet Harbors - 1999.mp3                                    | The Quiet Harbors | 1999                           |
        The Quiet Harbors - 12345678901 - Paper Lanterns.mp3            | The Quiet Harbors | 12345678901 - Paper Lanterns   |
        01-the_quiet_harbors-paper_lanterns-grp.mp3                     | the quiet harbors | paper lanterns                 | 1
        Ada-Vale_-_Paper_Lanterns.mp3                                   | Ada-Vale          | Paper Lanterns                 |
        05 - The Quiet Harbors - 1999.mp3                               | The Quiet Harbors | 1999                           | 5
        Music/The Quiet Harbors/Paper Lanterns.mp3                      |                   | Paper Lanterns                 |""",
    )
    fun `parses artist, title and track number`(fileName: String, artist: String?, title: String, track: Int?) {
        assertThat(FileNameParser.parse(fileName)).isEqualTo(ParsedFileName(artist, title, track))
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "The Quiet Harbors \u2013 Paper Lanterns.mp3",
            "The Quiet Harbors \u2014 Paper Lanterns.mp3",
            "The Quiet Harbors -- Paper Lanterns.mp3",
            "The%20Quiet%20Harbors%20-%20Paper%20Lanterns.mp3",
        ],
    )
    fun `accepts other dashes and encoded spaces as separators`(fileName: String) {
        assertThat(FileNameParser.parse(fileName)).isEqualTo(ParsedFileName("The Quiet Harbors", "Paper Lanterns", null))
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "AUD-20230101-WA0001.opus",
            "Track 05.mp3",
            "Audio 12.m4a",
            "12345.mp3",
            "Новая запись 5.m4a",
            "3f9a1c0b7d2e4f6a8b9c0d1e.mp3",
            "[320kbps].mp3",
        ],
    )
    fun `names without a title are unknown`(fileName: String) {
        assertThat(FileNameParser.parse(fileName)).isNull()
    }
}
