#!/usr/bin/env bash
# Runs the instrumented tests on the emulator that CI has booted, keeps going after a failure so that every group
# reports, and leaves the results in build/ci-results. EXCLUDED_TESTS lists what this run leaves out (notClass syntax);
# ABI_ARG limits the build to the emulator's ABI.
set -u

results=build/ci-results
runner_arg=-Pandroid.testInstrumentationRunnerArguments
package=io.github.lorddetson.piplayer
status=0

gradle() {
  local label="$1"
  shift
  ./gradlew "$ABI_ARG" "$@"
  local code=$?
  echo "::notice title=Gradle ${label}::exit code ${code}"
  [ "$code" -eq 0 ] || status=1
}

keep_results() {
  cp -r "$1/build/outputs/androidTest-results/connected" "$results/$2" || status=1
}

mkdir -p "$results"
adb shell svc power stayon true
adb logcat -v threadtime > "$results/logcat.txt" 2>/dev/null &
logcat=$!

exclusions=()
# The workflow may wrap the list over several lines; the runner wants it without spaces.
excluded="${EXCLUDED_TESTS:-}"
excluded="${excluded// /}"
if [ -n "$excluded" ]; then
  exclusions+=("$runner_arg.notClass=$excluded")
fi
gradle "main run" :app:connectedDebugAndroidTest :nativelib:connectedDebugAndroidTest --continue "${exclusions[@]}"
keep_results app app
keep_results nativelib nativelib

# Opt-in locally because Android counts repeated crashes against the app; this emulator is thrown away afterwards.
for test in tagging.TaggerCrashTest crash.CrashEndToEndTest; do
  gradle "$test" :app:connectedDebugAndroidTest "$runner_arg.crashTests=true" "$runner_arg.class=$package.$test"
  keep_results app "$test"
done

kill "$logcat" 2>/dev/null
python3 -I scripts/ci_test_report.py "app=$results/app" "nativelib=$results/nativelib" \
  "tagger crash=$results/tagging.TaggerCrashTest" "crash reports=$results/crash.CrashEndToEndTest" || status=1
exit "$status"
