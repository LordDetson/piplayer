# Privacy policy

This policy covers the 0.x pre-releases of PiPlayer published on GitHub. A full policy for the Google Play release
follows in M5.

## In short

- PiPlayer has no account, no analytics, no ads and no tracking. It sends no cookies and connects over HTTPS only.
- Your music and the library PiPlayer builds from it stay on your device. Your audio files never leave it.
- Only track identification talks to online services, and only after you choose **Enable** on the disclosure screen
  or turn on online lookups in Settings, "Identification and privacy". Until then PiPlayer sends nothing.
- You can turn online lookups off at any time in the same place. PiPlayer then stops at once, also in the middle of a
  lookup, and identifies tracks only from what the files already hold.

## What is sent when online lookups are on

| Service | What it receives |
|---|---|
| AcoustID (`api.acoustid.org`) | audio fingerprints and track lengths, with PiPlayer's application key |
| MusicBrainz (`musicbrainz.org`) | the files' existing tags, words from their file names, track lengths and MusicBrainz IDs |
| Cover Art Archive (`coverartarchive.org`) and the Internet Archive (`archive.org`) | MusicBrainz release and release group IDs |

Covers come from the Cover Art Archive also in the background: while albums without a cover of their own are shown in
lists, PiPlayer asks for their covers by release or release group ID.

Every service also sees your IP address and PiPlayer's User-Agent, which names the app, its version and this
project's address. [THIRD_PARTY_SERVICES.md](THIRD_PARTY_SERVICES.md) lists each service's terms and what it receives
in detail.

When PiPlayer shows a newer disclosure (a new service, or more data shared), online lookups stay off until you have
read it and chosen **Enable** again.

## What stays on your device

- Your choices, decisions and identification results are stored on the device. The settings and the user database go
  into Android's own backup and device transfer if you use them; the library database, caches and crash reports do
  not.
- A restored backup keeps your online-lookup choice, but only for the disclosure you accepted: a build that shows a
  newer disclosure asks again.
- Crash and problem reports leave the device only when you share them yourself.

## Contact

Questions about privacy: open an issue at https://github.com/LordDetson/piplayer/issues.
