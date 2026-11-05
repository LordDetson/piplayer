#!/usr/bin/env bash
# Runs the instrumented tests on the emulator that CI has booted, keeps going after a failure so that every group
# reports, and leaves the results in build/ci-results. EXCLUDED_TESTS lists what this run leaves out (notClass syntax);
# ABI_ARG limits the build to the emulator's ABI.
set -u

results=build/ci-results
runner_arg=-Pandroid.testInstrumentationRunnerArguments
crash_tests=io.github.lorddetson.piplayer.tagging.TaggerCrashTest,io.github.lorddetson.piplayer.crash.CrashEndToEndTest
status=0

mkdir -p "$results"
adb shell svc power stayon true
adb logcat -v threadtime > "$results/logcat.txt" 2>/dev/null &
logcat=$!

exclusions=()
if [ -n "${EXCLUDED_TESTS:-}" ]; then
  exclusions+=("$runner_arg.notClass=$EXCLUDED_TESTS")
fi

./gradlew "$ABI_ARG" :app:connectedDebugAndroidTest :nativelib:connectedDebugAndroidTest --continue "${exclusions[@]}" \
  || status=1
cp -r app/build/outputs/androidTest-results/connected "$results/app" || status=1
cp -r nativelib/build/outputs/androidTest-results/connected "$results/nativelib" || status=1

# Opt-in locally because Android counts repeated crashes against the app; this emulator is thrown away afterwards.
./gradlew "$ABI_ARG" :app:connectedDebugAndroidTest \
  "$runner_arg.crashTests=true" "$runner_arg.class=$crash_tests" || status=1
cp -r app/build/outputs/androidTest-results/connected "$results/crash" || status=1

kill "$logcat" 2>/dev/null
python3 -I scripts/ci_test_report.py "app=$results/app" "nativelib=$results/nativelib" "crash=$results/crash" || status=1
exit "$status"
