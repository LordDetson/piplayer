# M3 acceptance

The checks that close milestone M3 (features.md, M3 "Done when", TI §11.4) and the result with its date. The corpus check
of PIM-60 runs the whole pipeline over the owner's files against the real services, on real files, and is the milestone's
measure of quality; the phone runs that a device can only do by hand are listed as open.

**Verdict, 17.11.2026: the corpus holds; M3 is not closed yet.** The corpus check passed on 121 of the owner's files:
the high band held no confirmed wrong match (0 %, target at most 2 %), 7 files stayed unmatched, and the thresholds of
TI §4.1 stayed as they are. The quiet `--timing` pass on the S24 is green (14 of 14) after step 22's richer decisions
raised the write-join budget and the start-up-mirror bound by the owner's decision. Apply and Undo were checked by hand
on the S24: a wrong tag changed to the identified value on every surface and Undo brought it back. The phone-only runs
below (a real call the owner cannot make, notification and lock screen after Apply on Bluetooth and the watch, captive
portal, airplane mode) are still open and belong to the owner.

## Devices

| Device | Android | Build | Notes |
|---|---|---|---|
| Samsung Galaxy S24 Ultra (owner's phone, wireless debugging) | 17 (API 37) | debug, `13c5124` | the corpus files in `Music/corpus`, 121 files |

## The corpus (PIM-60, TI §11.4)

The corpus is 121 of the owner's own files from HomeMusic: 115 MP3, 2 M4A and 4 Opus, 18 with a Cyrillic name, picked as
every 13th file of the library in path order. They were unpacked into `Music/corpus` on the phone and indexed by the
media scanner. `OwnFilesCorpusRun` (an instrumented run, `-e liveNetwork true`) runs the app's pipeline with the debug
spike's services, reads each file's tags as the truth, and writes `results.tsv` and `summary.md` under the app's files.

Result over 121 files, 17.11.2026:

| Share | Value |
|---|---|
| High band | 81 of 121 (66.9 %) |
| Medium band | 33 of 121 (27.3 %) |
| Not found | 7 of 121 (5.8 %) |
| Top candidate correct, lenient | 93 of 121 (76.9 %) |
| Top candidate correct, strict | 76 of 121 (62.8 %) |

Wrong matches in the high band: 10 of 81 by the lenient criterion, and each of the ten is a comparison artifact, not a
wrong identification. One artist differs in writing only ("Fitz & The Tantrums" against "Fitz and the Tantrums",
"Majestic" against "Majestic x Boney M.", Cyrillic "Молчат Дома" against "Molchat Doma", "See Siang Wong" with a track
credited to the game's character). Two have the title in another writing ("インフェルノ" against "Inferno", Cyrillic or
Japanese against the Latin tag). Four carry tags the downloader gave, not the recording ("Astronomia" tagged Vicetone,
"Not The One" tagged DJ HAI CENA, "Still Alive" tagged to the in-game character). One is a remix whose tag names the
original ("Summertime (Imanbek remix)" tagged "Summertime Sadness"): the owner's file, so the owner decides whether that
one counts. Confirmed wrong identifications in the high band: 0 of 81.

Not found (7): five files the services do not know, one an empty AcoustID result left for a later retry, and one
"CITY LIGHT$ 2" whose name the query could not match. The thresholds of TI §4.1 (HIGH 0.90 with a 0.10 runner-up gap,
MEDIUM 0.50) stay as they are: a higher cut would push 66.9 % into the medium band for no measured gain, a lower one
would admit text-only matches.

## Open for the owner (device-only)

- A snippet while a real call interrupts: the owner cannot make a real call to check it; recorded as not checked.
  The snippet itself (play and stop) is checked by test.
- The notification and the lock screen after Apply and Undo on a Bluetooth device and on the watch (Apply and Undo on
  the phone were checked by hand and passed).
- Captive portal by hand: the owner has no such network to check it; recorded as not checked.
- Airplane mode by hand, and the archive.org redirect of the Cover Art Archive.

## Done on the S24, 17.11.2026

- Online lookups turned off: the disclosure shows in place of a run (checked by hand, the expected result).
- The quiet `--timing` pass: 14 of 14 green after the decided-value budgets were raised for step 22's rich decisions
  (`GeneratedLibraryDecisionsTest`, push 93c6164).
- One live `DeviceFingerprintTest` run with `-e liveNetwork true`: 24 tests, the live AcoustID path the corpus also
  exercised.
- A smoke run of the minified `benchmark` build: `MinifiedClientsTest` with `-e minified true`, 13 of 13.