# Third-party services

PiPlayer plays and manages your music without a network. Only track identification talks to online services, and only
after you turn online lookups on and accept the disclosure in the app. Until then the app sends nothing (see
[track-identification.md](track-identification.md) §9, "Privacy", and the [privacy policy](PRIVACY.md)).

This page lists every service PiPlayer talks to, where its terms are, when PiPlayer's maintainer last read them and
what the service receives. It is updated whenever a service is added or its terms change, and every term is read
again before the first Google Play release.

## What every service receives

- Your IP address, as with any connection.
- The User-Agent `PiPlayer/<version> ( <contact> )`, where the contact is this project's repository or, in a fork,
  the fork's own URL or e-mail address.
- Nothing else about you: PiPlayer has no account, sends no cookies, runs no analytics and connects over HTTPS only.
  Your audio files themselves never leave the device; only what each service lists below does.

## Services in use

### AcoustID and Chromaprint

- **Used for:** finding the recording from an audio fingerprint (identification stage 3, from M3).
- **Host:** `api.acoustid.org`.
- **Receives:** the Chromaprint fingerprint of the first two minutes of the file, the file's length in whole seconds
  and PiPlayer's application key. The key ships in the app and is not a secret.
- **Terms and licences** (last read 2026-10-18):
  - Web service and its terms: https://acoustid.org/webservice
  - Plans, including the free Open Source plan: https://acoustid.biz/
  - Data licence (CC BY-SA 3.0; the links to MusicBrainz recordings are CC0): https://acoustid.org/license
- **In writing:** on 2026-11-05 AcoustID confirmed that a free GPL app without ads or paid features, with donations
  through an external link, qualifies for the free Open Source plan; that the rate limit counts per IP address, not
  per application key; and that batches of 10 to 20 fingerprints per request are fine.
- **Builds:** every build carries one key until M5, when the GitHub and the Google Play builds get one each. A build
  without a key skips this service and identifies tracks by the other stages (known IDs, ISRC, text search).

### MusicBrainz

- **Used for:** recordings, releases and their details: lookups by ID and ISRC, text search, release browsing
  (identification stages 1, 2, 4, 5 and 6, from M3).
- **Host:** `musicbrainz.org`.
- **Receives:** search terms built from the file's existing tags and its parsed file name (title, artist, album,
  track number), the file's length, and MusicBrainz IDs and ISRCs from the tags or from an earlier stage.
- **Terms and licences** (last read 2026-10-18):
  - Web service: https://musicbrainz.org/doc/MusicBrainz_API
  - Rate limiting and the User-Agent rule: https://musicbrainz.org/doc/MusicBrainz_API/Rate_Limiting
  - Data licences (core data CC0; tags, genres and other supplementary data CC BY-NC-SA, which PiPlayer never
    requests): https://musicbrainz.org/doc/MusicBrainz_Database
  - Supporter tiers, including the free non-profit tier: https://metabrainz.org/supporters/account-type
- **In writing:** on 2026-10-30 MetaBrainz confirmed that public pre-releases may use the free web service with a
  User-Agent that names a contact. The same reply asked PiPlayer to contact MetaBrainz again about commercial terms
  if donations exceed $500 a year. PiPlayer registers at metabrainz.org (non-profit tier) before the Google Play
  release (M5).

### Cover Art Archive and the Internet Archive

- **Used for:** front covers of releases and release groups (identification stage 7, and covers in lists, from M3).
- **Hosts:** `coverartarchive.org`, which redirects image requests to the Internet Archive (`archive.org`).
- **Receives:** the MusicBrainz ID of the release or release group in the request path. Covers of albums without a
  cover of their own are also fetched in the background while such albums are shown in lists.
- **Terms** (last read 2026-10-18):
  - Service: https://coverartarchive.org/
  - API: https://musicbrainz.org/doc/Cover_Art_Archive/API
  - Policy (covers stay copyrighted by their rights holders, "use at your own risk"):
    https://musicbrainz.org/doc/Cover_Art_Archive
  - The Internet Archive's own terms: not read yet; to be read before the Google Play release.

## Planned services

These are not used by any build yet. Each gets its own section here before it ships (see
[track-identification.md](track-identification.md) §9):

- GitHub Releases API: an opt-in update check in the GitHub build (M5).
- Radio Browser: the station directory of internet radio (v1.x).
- LRCLIB: synced lyrics on request (v1.x).

## Forks

A fork registers its own AcoustID application key and puts its own contact into the User-Agent, as the
[README](../README.md#forks) explains. The services then see the fork, not this project.
