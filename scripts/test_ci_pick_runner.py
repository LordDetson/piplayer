"""Tests for ci_pick_runner.py: python -m unittest discover -s scripts -p "test_*.py"."""
import json
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ci_pick_runner as pick  # noqa: E402

NOW = 1_800_000_000
FRESH = str(NOW - 120)
TUF_LABELS = ["self-hosted", "linux", "x64", "piplayer-tuf"]


def job(status, labels=TUF_LABELS):
    return {"status": status, "labels": labels}


class ChooseTest(unittest.TestCase):
    def choose(self, switch="", seen=FRESH, other_jobs=()):
        return pick.choose(switch=switch, seen=seen, now=NOW, other_jobs=list(other_jobs))

    def test_aFreshHeartbeatAndNoOtherTufJobSendTheJobToTuf(self):
        runner, _ = self.choose()
        self.assertEqual(pick.TUF, runner)

    def test_theSwitchSetToOffKeepsTheJobOnHostedRunners(self):
        runner, reason = self.choose(switch="off")
        self.assertEqual(pick.HOSTED, runner)
        self.assertIn("off", reason)

    def test_theSwitchIsCaseInsensitive(self):
        self.assertEqual(pick.HOSTED, self.choose(switch="OFF")[0])

    def test_noHeartbeatAtAllMeansHosted(self):
        self.assertEqual(pick.HOSTED, self.choose(seen="")[0])

    def test_aHeartbeatThatIsNotANumberMeansHosted(self):
        self.assertEqual(pick.HOSTED, self.choose(seen="yesterday")[0])

    def test_aHeartbeatOlderThanFifteenMinutesMeansHosted(self):
        runner, reason = self.choose(seen=str(NOW - 15 * 60 - 1))
        self.assertEqual(pick.HOSTED, runner)
        self.assertIn("silent", reason)

    def test_aHeartbeatOfExactlyFifteenMinutesStillCounts(self):
        self.assertEqual(pick.TUF, self.choose(seen=str(NOW - 15 * 60))[0])

    def test_aQueuedTufJobOfAnotherRunMeansHosted(self):
        runner, reason = self.choose(other_jobs=[job("queued")])
        self.assertEqual(pick.HOSTED, runner)
        self.assertIn("busy", reason)

    def test_anInProgressTufJobOfAnotherRunMeansHosted(self):
        self.assertEqual(pick.HOSTED, self.choose(other_jobs=[job("in_progress")])[0])

    def test_aWaitingTufJobOfAnotherRunMeansHosted(self):
        self.assertEqual(pick.HOSTED, self.choose(other_jobs=[job("waiting")])[0])

    def test_aCompletedTufJobDoesNotBlockTuf(self):
        self.assertEqual(pick.TUF, self.choose(other_jobs=[job("completed")])[0])

    def test_aBusyHostedJobDoesNotBlockTuf(self):
        self.assertEqual(pick.TUF, self.choose(other_jobs=[job("in_progress", ["ubuntu-24.04"])])[0])

    def test_unknownOtherJobsMeanHosted(self):
        # None: the jobs of other runs could not be read, so tuf may be busy.
        runner, reason = pick.choose(switch="", seen=FRESH, now=NOW, other_jobs=None)
        self.assertEqual(pick.HOSTED, runner)
        self.assertIn("unknown", reason)


class OutputTest(unittest.TestCase):
    def test_bothChoicesAreJsonForFromJson(self):
        self.assertEqual("ubuntu-24.04", json.loads(pick.HOSTED))
        self.assertEqual(TUF_LABELS, json.loads(pick.TUF))


class OtherJobsTest(unittest.TestCase):
    def test_jobsOfTheCurrentRunAreLeftOut(self):
        runs = {"queued": [{"id": 1}], "in_progress": [{"id": 2}]}
        jobs = {1: [job("queued")], 2: [job("in_progress", ["ubuntu-24.04"])]}
        found = pick.other_jobs(current_run=1, list_runs=lambda status: runs[status], list_jobs=lambda run: jobs[run])
        self.assertEqual([job("in_progress", ["ubuntu-24.04"])], found)

    def test_aFailedApiCallGivesNone(self):
        def broken(status):
            raise OSError("no network")
        self.assertIsNone(pick.other_jobs(current_run=1, list_runs=broken, list_jobs=lambda run: []))


if __name__ == "__main__":
    unittest.main()
