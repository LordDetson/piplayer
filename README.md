# PiPlayer

A music player for Android that can identify your tracks. Pick a file with a meaningless name, and PiPlayer finds
the artist, title, album, release date and cover art in open music databases, shows you what will change and saves
the result only after you confirm.

Built with Kotlin, Jetpack Compose and Media3.

## Status

Early design. There is no app code yet; the first specification is in
[docs/track-identification.md](docs/track-identification.md).

## Planned data sources

- [AcoustID](https://acoustid.org/) and [Chromaprint](https://github.com/acoustid/chromaprint) for audio fingerprints
- [MusicBrainz](https://musicbrainz.org/) for metadata (CC0 core data)
- [Cover Art Archive](https://coverartarchive.org/) for album covers

## License

PiPlayer is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
later version. See [LICENSE](LICENSE).

The name "PiPlayer" and the PiPlayer logo are not covered by this license. If you publish a modified version,
please give it a different name and icon.
