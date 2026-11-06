"""Turns the JUnit XML of an instrumented run into GitHub annotations and the job summary.

Annotations can be read without signing in, unlike the logs and artifacts of a run, so the totals and every failed
test's name go there.

Usage:
  python scripts/ci_test_report.py [--not-expected method,...] <label>=<results dir>[=<retry results dir>] ...
      A test that failed and then passed in the retry counts as flaky: a warning, not a failure. Exits with 1 when a
      test failed (in the retry too, when there was one), a group has no results (an instrumentation process that died
      writes none), or a test named in --not-expected ran.
  python scripts/ci_test_report.py --failed-classes <results dir>
      Prints the classes with failed tests, comma separated, for a retry.
"""
import os
import pathlib
import re
import sys
import xml.etree.ElementTree as ElementTree

# GitHub shows at most 10 annotations of each level per step; one more lists every name.
MAX_NAMED_ANNOTATIONS = 8
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


def failed_classes(directory):
    names = [name for name, _, _, failure in read_group(directory) if failure is not None]
    return ",".join(sorted({name.split("#", 1)[0] for name in names}))


def method_of(name):
    """The method's name without a parameterized suffix such as [flac]."""
    return re.sub(r"\[.*\]$", "", name.split("#", 1)[1])


def annotate_names(level, what, items):
    for name, text in items[:MAX_NAMED_ANNOTATIONS]:
        annotate(level, name, text.strip()[:MAX_MESSAGE_CHARS])
    if items:
        annotate(level, f"{len(items)} {what}", ", ".join(name for name, _ in items))


def report(arguments, not_expected):
    failed_anywhere = False
    summary = ["| Group | Tests | Failed | Flaky | Skipped | Seconds |", "|---|---:|---:|---:|---:|---:|"]
    all_failures, all_flaky = [], []
    for argument in arguments:
        label, directory, *retry = argument.split("=")
        cases = read_group(directory)
        failures = [(name, text) for name, _, _, text in cases if text is not None]
        flaky = []
        if retry and failures:
            passed_on_retry = {name for name, _, skipped, text in read_group(retry[0]) if text is None and not skipped}
            flaky = [(name, text) for name, text in failures if name in passed_on_retry]
            failures = [(name, text) for name, text in failures if name not in passed_on_retry]
        skipped = sum(1 for _, _, is_skipped, _ in cases if is_skipped)
        seconds = round(sum(time for _, time, _, _ in cases))
        if not cases:
            annotate("error", f"{label}: no results", f"No TEST-*.xml under {directory}")
            failed_anywhere = True
        unexpected = [name for name, _, _, _ in cases if method_of(name) in not_expected]
        if unexpected:
            annotate("error", f"{label}: excluded tests ran", ", ".join(unexpected))
            failed_anywhere = True
        names = f": {', '.join(name for name, _, _, _ in cases)}" if len(cases) <= MAX_NAMED_CASES else ""
        annotate("notice", f"{label}: {len(cases)} tests",
                 f"{len(failures)} failed, {len(flaky)} flaky, {skipped} skipped, {seconds} s{names}")
        summary.append(f"| {label} | {len(cases)} | {len(failures)} | {len(flaky)} | {skipped} | {seconds} |")
        all_failures += failures
        all_flaky += flaky

    annotate_names("warning", "flaky tests (failed, then passed on the retry)", all_flaky)
    annotate_names("error", "failed tests", all_failures)
    if all_failures:
        failed_anywhere = True
    for title, items in (("Failed", all_failures), ("Flaky", all_flaky)):
        if items:
            summary += ["", f"{title}:", *(f"- `{name}`" for name, _ in items)]

    summary_file = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_file:
        with open(summary_file, "a", encoding="utf-8") as out:
            out.write("\n".join(summary) + "\n")
    return 1 if failed_anywhere else 0


def main():
    arguments = sys.argv[1:]
    if arguments[:1] == ["--failed-classes"]:
        print(failed_classes(arguments[1]))
        return 0
    not_expected = set()
    if arguments[:1] == ["--not-expected"]:
        not_expected = {name for name in arguments[1].split(",") if name}
        arguments = arguments[2:]
    return report(arguments, not_expected)


if __name__ == "__main__":
    sys.exit(main())
