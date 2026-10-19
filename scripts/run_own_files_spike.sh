#!/usr/bin/env bash
# Runs the M0 identification spike over a folder of the owner's own audio files on the connected device or emulator
# and pulls one JSON line per file (TI §11.4). The files and the results are private: keep both outside git.
#
# Usage: scripts/run_own_files_spike.sh <folder with audio files> <output .jsonl>
# Needs adb on PATH and an AcoustID application key in local.properties (acoustid.apiKey=...); without a key the
# fingerprint lookups are skipped. Installing the test APK also prepares the fingerprint test recordings, which needs
# Python, network access on the first run, and fpcalc 1.6.2 (FPCALC=<path>, or the copy under tools/). Run from the
# repository root with JAVA_HOME pointing at JDK 17.
#
# The files go into the debug app's private storage through run-as: a folder that adb creates under Android/data
# belongs to the shell user, and the app cannot read it.
set -euo pipefail

if [ $# -ne 2 ]; then
  echo "Usage: $0 <folder with audio files> <output .jsonl>" >&2
  exit 2
fi
SRC="$1"
OUT="$2"
PKG=io.github.lorddetson.piplayer

# Git Bash on Windows rewrites arguments that look like paths; the device paths must reach adb as they are.
export MSYS_NO_PATHCONV=1

LOG="$(mktemp)"
# The owner's files never stay on the device, whatever happens below.
cleanup() {
  adb shell run-as "$PKG" rm -rf files/own-files files/spike > /dev/null 2>&1 || true
  rm -f "$LOG"
}
trap cleanup EXIT

./gradlew :app:installDebug :app:installDebugAndroidTest --console=plain -q
# tar keeps the names (spaces, Cyrillic) and the folders; exec-in passes the bytes through unchanged.
UNPACK='rm -rf files/own-files files/spike && mkdir -p files/own-files && tar -xf - -C files/own-files'
tar -cf - -C "$SRC" . | adb exec-in run-as "$PKG" sh -c "$UNPACK"
adb shell am instrument -w -e ownFiles true -e class "$PKG.spike.OwnFilesSpikeRun" \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > "$LOG" 2>&1 || true
cat "$LOG"
# Pulled before the run is judged: a run that stopped part way still leaves the lines it wrote.
adb exec-out run-as "$PKG" cat files/spike/own-files.jsonl > "$OUT" || echo "No results to pull" >&2
if ! grep -q 'OK (1 test)' "$LOG"; then
  echo "The run did not finish cleanly; whatever it wrote is in $OUT" >&2
  exit 1
fi
echo "Results: $OUT"
