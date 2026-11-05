"""Turns the JUnit XML of an instrumented run into GitHub annotations and the job summary.

Annotations can be read without signing in, unlike the logs and artifacts of a run, so the totals and every failed
test's name go there.

Usage: python scripts/ci_test_report.py <label>=<results dir> [<label>=<results dir> ...]
Exits with 1 when a test failed or a group has no results (an instrumentation process that died writes none).
"""
import os
import pathlib
import sys
import xml.etree.ElementTree as ElementTree

# GitHub shows at most 10 error annotations per step; one more lists every failed test.
MAX_FAILURE_ANNOTATIONS = 8
MAX_MESSAGE_CHARS = 400
# A group this small lists its tests by name, so that a run that stopped early shows which ones ran.
MAX_NAMED_CASES = 5
ASSUMPTION = "org.junit.AssumptionViolatedException"


def escaped(text):
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def annotate(level, title, message):
    title = escaped(title).replace(":", "%3A").replace(",", "%2C")
    print(f"::{level} title={title}::{escaped(message)}")


def read_group(directory):
    """All test cases under the directory, as (name, seconds, skipped, failure text or None)."""
    cases = []
    for path in sorted(pathlib.Path(directory).rglob("TEST-*.xml")):
        for case in ElementTree.parse(path).getroot().iter("testcase"):
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            failure = None if problem is None else (problem.text or problem.get("message") or "no message")
            skipped = case.find("skipped") is not None
            # The connected test report writes a test that an assumption skipped as a failure.
            if failure is not None and ASSUMPTION in failure:
                failure, skipped = None, True
            cases.append((f"{case.get('classname')}#{case.get('name')}", float(case.get("time", "0")), skipped, failure))
    return cases


def main():
    failed_anywhere = False
    summary = ["| Group | Tests | Failed | Skipped | Seconds |", "|---|---:|---:|---:|---:|"]
    all_failures = []
    for argument in sys.argv[1:]:
        label, directory = argument.split("=", 1)
        cases = read_group(directory)
        failures = [(name, text) for name, _, _, text in cases if text is not None]
        skipped = sum(1 for _, _, is_skipped, _ in cases if is_skipped)
        seconds = round(sum(time for _, time, _, _ in cases))
        if not cases:
            annotate("error", f"{label}: no results", f"No TEST-*.xml under {directory}")
            failed_anywhere = True
        names = f": {', '.join(name for name, _, _, _ in cases)}" if len(cases) <= MAX_NAMED_CASES else ""
        annotate("notice", f"{label}: {len(cases)} tests",
                 f"{len(failures)} failed, {skipped} skipped, {seconds} s{names}")
        summary.append(f"| {label} | {len(cases)} | {len(failures)} | {skipped} | {seconds} |")
        all_failures += failures

    for name, text in all_failures[:MAX_FAILURE_ANNOTATIONS]:
        annotate("error", name, text.strip()[:MAX_MESSAGE_CHARS])
    if all_failures:
        failed_anywhere = True
        names = ", ".join(name for name, _ in all_failures)
        annotate("error", f"{len(all_failures)} failed tests", names)
        summary += ["", "Failed:", *(f"- `{name}`" for name, _ in all_failures)]

    summary_file = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_file:
        with open(summary_file, "a", encoding="utf-8") as out:
            out.write("\n".join(summary) + "\n")
    return 1 if failed_anywhere else 0


if __name__ == "__main__":
    sys.exit(main())
