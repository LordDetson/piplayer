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

What each service receives, and where its terms are, is listed in
[docs/THIRD_PARTY_SERVICES.md](docs/THIRD_PARTY_SERVICES.md).

## Forks

PiPlayer's builds name themselves to these services. A fork you publish should speak for itself:

1. Register your own AcoustID application key at [acoustid.org](https://acoustid.org/new-application) and put it into
   `local.properties`, which git ignores:

   ```properties
   acoustid.apiKey=YourKey
   ```

   Gradle's `-Pacoustid.apiKey=YourKey` works too, but Gradle lists command-line properties in its `properties`
   report, so `local.properties` is the better place. The key may contain only letters and digits, and every build
   type uses it. Without a key the app still builds and works: it skips fingerprint lookups and identifies tracks by
   the other stages (known IDs, ISRC, text search).
2. Set your own contact for the User-Agent, a URL or an e-mail address, in `gradle.properties` or with
   `-Ppiplayer.contactUrl=...`:

   ```properties
   piplayer.contactUrl=https://github.com/you/your-fork
   ```

   It must be one token of printable ASCII without spaces, quotes, backslashes or parentheses. Without it the
   User-Agent names this repository, and
   MusicBrainz would contact this project about your users' traffic.
3. Give the fork a different name and icon (see License).

## License

PiPlayer is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
later version. See [LICENSE](LICENSE).

The name "PiPlayer" and the PiPlayer logo are not covered by this license. If you publish a modified version,
please give it a different name and icon.
