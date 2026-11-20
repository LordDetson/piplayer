# HomeMusic integration

The first public PiPlayer APK includes all three parts of PIM-100. PiPlayer connects to the user's existing
HomeMusic installation. An account and server profile are added in PiPlayer's settings. The Play build has a
separate release review (PIM-42, PIM-101). The Play scope covers HTTPS access to the existing Navidrome library,
synchronization and authorized offline copies. Any Hub recommendation or download action exposed in Play must
meet the applicable terms and content rights, including automatic acquisition triggered by a mix.

## Server profile

Keep the Navidrome and Hub URLs separate. Accept HTTP and HTTPS, preserve a reverse proxy's base path and add
only fixed API paths or encoded identifiers. Validate URLs before making a request. Credentials and session
state are scoped to the configured service. Connection checks report an unreachable service, a rejected login,
an expired session or an unsupported API separately. HTTPS is the default and is required in the Play build.
The forum build can explicitly enable cleartext for the user's own installation on the local network.

## Library and playback

Read albums, artists, tracks, covers, favorites and playlists using Navidrome's Subsonic API. Expose the server
library as a source in PiPlayer, with browse, search, selection and queue controls. Identify server tracks by
the profile and server track ID. Stream and seek through the normal player. Existing server playlists include
HomeMusic's daily mix when the Hub has generated it.

## Offline and synchronization

Downloads to this device use app storage and can play after a restart with the network off. Write to a temporary
file until the download completes. Retry without creating duplicate playable entries. Present download progress
and storage controls. Synchronize favorite and playlist edits with the user's server. Retain pending changes if
the connection drops, detect changes from another client and keep the server playlist order.

## HomeMusic Hub

Sign in to the user-configured Hub. Show the current recommendations and daily mix settings. Start and continue
a HomeMusic wave through the published API. Offer server download requests and show their status and actions.
Distinguish server download jobs from files saved for offline playback on the device. Hub features depend on
what the user's installation exposes.

## Verification

Start with URL, authentication, session and response contract tests against controlled fixtures. Verify browsing,
search, streaming and seeking on API 30 and 36. Cover a rejected login, an unavailable host, partly read streams,
interrupted downloads, process death, offline line-up, playlist ordering and reconciliation after reconnecting.
Check that problem reports and diagnostics omit credentials and session identifiers.

The implementation follows the published [HomeMusic overview](https://github.com/justman-1/HomeMusic-public),
[Hub API](https://github.com/justman-1/HomeMusic-public/blob/master/hub/API.md) and
[Navidrome Subsonic compatibility](https://www.navidrome.org/docs/developers/subsonic-api/).
