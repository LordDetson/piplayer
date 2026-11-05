#!/usr/bin/env bash
# Runs the instrumented tests on the emulator that CI has booted and leaves the results in build/ci-results. The tests
# with a wall-clock limit (@TimingTest) stay out, as in the owner's ordinary runs. A class with a failed test runs once
# more, so that the report can tell a flaky test from a broken one. ABI_ARG limits the build to the emulator's ABI.
set -u

results=build/ci-results
runner_arg=-Pandroid.testInstrumentationRunnerArguments
package=io.github.lorddetson.piplayer
no_timing="$runner_arg.notAnnotation=$package.TimingTest"
# A retry is for a few flaky classes; more failures than this point at the build or the emulator.
max_retried_classes=5
status=0

gradle() {
  local label="$1"
  shift
  ./gradlew "$ABI_ARG" "$@"
  local code=$?
  echo "::notice title=Gradle ${label}::exit code ${code}"
  return "$code"
}

keep_results() {
  cp -r "$1/build/outputs/androidTest-results/connected" "$results/$2" || status=1
}

mkdir -p "$results"
adb shell svc power stayon true
adb logcat -v threadtime > "$results/logcat.txt" 2>/dev/null &
logcat=$!

gradle "main run" :app:connectedDebugAndroidTest :nativelib:connectedDebugAndroidTest --continue "$no_timing"
main_code=$?
keep_results app app
keep_results nativelib nativelib

retried=0
for module in app nativelib; do
  classes="$(python3 -I scripts/ci_test_report.py --failed-classes "$results/$module")"
  [ -n "$classes" ] || continue
  retried=1
  if [ "$(tr ',' '\n' <<< "$classes" | wc -l)" -le "$max_retried_classes" ]; then
    gradle "$module retry" ":$module:connectedDebugAndroidTest" "$no_timing" "$runner_arg.class=$classes"
    keep_results "$module" "$module-retry"
  fi
done
# A failed Gradle run with no failed test (an install error, a crashed instrumentation) is a failure of its own.
if [ "$main_code" -ne 0 ] && [ "$retried" -eq 0 ]; then
  status=1
fi

# Opt-in locally because Android counts repeated crashes against the app; this emulator is thrown away afterwards.
for test in tagging.TaggerCrashTest crash.CrashEndToEndTest; do
  gradle "$test" :app:connectedDebugAndroidTest "$runner_arg.crashTests=true" "$runner_arg.class=$package.$test" \
    || status=1
  keep_results app "$test"
done

kill "$logcat" 2>/dev/null
timing_tests="$(grep -rh -A2 '@TimingTest' app/src/androidTest nativelib/src/androidTest \
  | sed -nE 's/.*fun ([A-Za-z0-9_]+).*/\1/p' | paste -sd ',' -)"
python3 -I scripts/ci_test_report.py --not-expected "$timing_tests" \
  "app=$results/app=$results/app-retry" "nativelib=$results/nativelib=$results/nativelib-retry" \
  "tagger crash=$results/tagging.TaggerCrashTest" "crash reports=$results/crash.CrashEndToEndTest" || status=1
exit "$status"
