#!/usr/bin/env bash
# TEMPORARY (narrowed run of PIM-50 review fixes only): the touched test class, without retries.
set -u
results=build/ci-results
runner_arg=-Pandroid.testInstrumentationRunnerArguments
package=io.github.lorddetson.piplayer
tests="ui.PiPlayerAppTest"
mkdir -p "$results"
adb shell svc power stayon true
adb logcat -v threadtime > "$results/logcat.txt" 2>/dev/null &
logcat=$!
groups=()
n=0
for test in $tests; do
  n=$((n + 1))
  ./gradlew "$ABI_ARG" :app:connectedDebugAndroidTest "$runner_arg.class=$package.$test" "$runner_arg.notAnnotation=$package.TimingTest"
  cp -r app/build/outputs/androidTest-results/connected "$results/run$n"
  groups+=("run$n=$results/run$n")
done
kill "$logcat" 2>/dev/null
python3 -I scripts/ci_test_report.py "${groups[@]}"
