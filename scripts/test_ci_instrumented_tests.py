"""Tests for ci_instrumented_tests.sh with a fake gradlew and adb: python -m unittest discover -s scripts -p "test_*.py"."""
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent
PACKAGE = "io.github.lorddetson.piplayer"

# Records every call and writes a connected test report for each module it runs: the main run fails one test in each
# class listed in FAIL_CLASSES; a run limited to classes passes them.
FAKE_GRADLEW = r"""#!/usr/bin/env bash
echo "$*" >> gradle-calls.txt
classes=""
for arg in "$@"; do
  case "$arg" in
    -Pandroid.testInstrumentationRunnerArguments.class=*) classes="${arg#*=}" ;;
  esac
done
for module in app nativelib; do
  case " $* " in *":$module:connectedDebugAndroidTest"*) ;; *) continue ;; esac
  out="$module/build/outputs/androidTest-results/connected"
  rm -rf "$out"
  mkdir -p "$out"
  {
    echo '<testsuite>'
    if [ -z "$classes" ] && [ "$module" = app ]; then
      for class in $FAIL_CLASSES; do
        echo "<testcase classname=\"$class\" name=\"flaky\"><failure>boom</failure></testcase>"
      done
    elif [ -z "$classes" ]; then
      echo "<testcase classname=\"$module.NativeTest\" name=\"passes\"/>"
    else
      # Only the first class of a comma list runs, as AGP was seen doing on CI (PIM-90).
      first="${classes%%,*}"
      [ -n "$first" ] && echo "<testcase classname=\"$first\" name=\"flaky\"/>"
    fi
    echo '</testsuite>'
  } > "$out/TEST-$module.xml"
done
exit 0
"""

FAKE_ADB = "#!/usr/bin/env bash\nexit 0\n"


class RetryTest(unittest.TestCase):
    def run_script(self, fail_classes):
        with tempfile.TemporaryDirectory() as root:
            root = pathlib.Path(root)
            (root / "scripts").mkdir()
            for name in ("ci_instrumented_tests.sh", "ci_test_report.py"):
                shutil.copy(SCRIPTS / name, root / "scripts" / name)
            (root / "app/src/androidTest").mkdir(parents=True)
            (root / "nativelib/src/androidTest").mkdir(parents=True)
            (root / "gradlew").write_text(FAKE_GRADLEW, newline="\n")
            bin_dir = root / "bin"
            bin_dir.mkdir()
            (bin_dir / "adb").write_text(FAKE_ADB, newline="\n")
            # python3 may be missing (Windows) or another interpreter: run the one running these tests.
            (bin_dir / "python3").write_text(f'#!/usr/bin/env bash\nexec "{pathlib.Path(sys.executable).as_posix()}" "$@"\n', newline="\n")
            for script in (root / "gradlew", bin_dir / "adb", bin_dir / "python3"):
                script.chmod(0o755)
            env = dict(os.environ, ABI_ARG="-Pabi=x86_64", FAIL_CLASSES=" ".join(fail_classes))
            env["PATH"] = f"{bin_dir}{os.pathsep}{env['PATH']}"
            # By its full path: on Windows a bare "bash" may start WSL instead of the bash on PATH.
            result = subprocess.run([shutil.which("bash"), "scripts/ci_instrumented_tests.sh"], cwd=root, env=env, capture_output=True, text=True)
            calls = (root / "gradle-calls.txt").read_text().splitlines()
            return result, calls

    def retries(self, calls):
        return [c for c in calls if ".class=" in c and "crashTests" not in c]

    def test_twoFlakyClassesAreEachRetriedAndTheRunPasses(self):
        a, b = f"{PACKAGE}.playback.FirstTest", f"{PACKAGE}.library.SecondTest"
        result, calls = self.run_script([a, b])
        self.assertTrue(all("-Pabi=x86_64" in call.split() for call in calls), calls)
        retried = self.retries(calls)
        self.assertEqual(2, len(retried), calls)
        self.assertTrue(any(c.endswith(f".class={a}") for c in retried), retried)
        self.assertTrue(any(c.endswith(f".class={b}") for c in retried), retried)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("flaky tests", result.stdout)

    def test_oneFlakyClassIsRetriedOnce(self):
        a = f"{PACKAGE}.playback.FirstTest"
        result, calls = self.run_script([a])
        self.assertEqual(1, len(self.retries(calls)), calls)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_moreFailedClassesThanTheLimitAreNotRetriedAndTheRunFails(self):
        many = [f"{PACKAGE}.c.Test{n}" for n in range(6)]
        result, calls = self.run_script(many)
        self.assertEqual([], self.retries(calls), calls)
        self.assertNotEqual(0, result.returncode)


if __name__ == "__main__":
    unittest.main()
