"""Picks the runner for one instrumented leg: the owner's self-hosted runner on tuf when it is alive and free, else a
GitHub-hosted runner.

tuf is alive when its spawner wrote the repository variable TUF_RUNNER_SEEN (Unix time) in the last 15 minutes, and
free when no queued or running job of another run asks for the label piplayer-tuf. The repository variable TUF_RUNNER
set to "off" keeps every leg on hosted runners. Any doubt means hosted: a hosted runner is never worse than a queue.

Prints "runner=<JSON>" for $GITHUB_OUTPUT (a string or a label list, for fromJSON in runs-on) and a notice line.
Environment: TUF_RUNNER, TUF_RUNNER_SEEN, GITHUB_TOKEN (actions: read), GITHUB_REPOSITORY, GITHUB_RUN_ID.
"""
import json
import os
import sys
import time
import urllib.request

LABEL = "piplayer-tuf"
HOSTED = json.dumps("ubuntu-24.04")
TUF = json.dumps(["self-hosted", "linux", "x64", LABEL])
MAX_SILENCE_S = 15 * 60
BUSY = {"queued", "in_progress", "waiting", "pending", "requested"}


def choose(switch, seen, now, other_jobs):
    """Returns (runner JSON, reason). other_jobs: the jobs of other active runs, or None when they are unknown."""
    if switch.strip().lower() == "off":
        return HOSTED, "TUF_RUNNER is off"
    try:
        silence = now - int(seen)
    except ValueError:
        return HOSTED, "tuf has never reported"
    if silence > MAX_SILENCE_S:
        return HOSTED, f"tuf silent for {silence // 60} min"
    if other_jobs is None:
        return HOSTED, "tuf load unknown"
    if any(j["status"] in BUSY and LABEL in j.get("labels", []) for j in other_jobs):
        return HOSTED, "tuf busy with another run"
    return TUF, f"tuf alive ({silence} s ago) and free"


def other_jobs(current_run, list_runs, list_jobs):
    """The jobs of every queued or running run but this one, or None when the API fails."""
    try:
        found = []
        for status in ("queued", "in_progress"):
            for run in list_runs(status):
                if run["id"] != current_run:
                    found.extend(list_jobs(run["id"]))
        return found
    except Exception as e:  # noqa: BLE001 - any failure means "unknown", which falls back to hosted
        print(f"::warning title=Runner choice::cannot read other runs: {e}", file=sys.stderr)
        return None


def _get(path):
    req = urllib.request.Request(
        f"https://api.github.com/repos/{os.environ['GITHUB_REPOSITORY']}{path}",
        headers={"Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}", "Accept": "application/vnd.github+json",
                 "X-GitHub-Api-Version": "2022-11-28"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.load(r)


def main():
    jobs = other_jobs(
        current_run=int(os.environ["GITHUB_RUN_ID"]),
        list_runs=lambda status: _get(f"/actions/runs?status={status}&per_page=50")["workflow_runs"],
        list_jobs=lambda run: _get(f"/actions/runs/{run}/jobs?filter=latest&per_page=100")["jobs"])
    runner, reason = choose(os.environ.get("TUF_RUNNER", ""), os.environ.get("TUF_RUNNER_SEEN", ""),
                            int(time.time()), jobs)
    print(f"runner={runner}")
    print(f"::notice title=Runner choice::{json.loads(runner)} ({reason})", file=sys.stderr)


if __name__ == "__main__":
    main()
