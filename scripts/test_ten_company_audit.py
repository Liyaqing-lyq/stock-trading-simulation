"""标准十公司审计共享的版本身份、清单事件和十一版增量规则回归。"""
import json
import unittest
from copy import deepcopy

from audit_ten_company_run import OPENING_POLICIES, checked_opening, checked_reread


class OpeningPolicyTest(unittest.TestCase):
    old = "web-ten-company-autonomy-v10"
    new = "web-ten-company-autonomy-v11"

    def frame(self, run_id, policies, manifest_id=None):
        text = json.dumps({"manifest": {"runId": manifest_id or run_id,
                          "dataVersions": {"branch-profile-run": run_id, **policies}}})
        return {"position": 1, "runId": run_id, "commandContent": text,
                "events": [{"type": "RUN_MANIFEST_V1", "payload": text}]}

    def test_declared_version_controls_all_seven_new_policies(self):
        previous, current = OPENING_POLICIES[self.old], OPENING_POLICIES[self.new]
        additions = current.keys() - previous.keys()
        self.assertEqual(additions, {"personal-news-ranking", "news-exploration-slot", "market-session-wake",
                         "media-subscriptions", "cross-border-editing", "pharma-licensing", "hydro-inflow-path"})
        for run_id, policies in OPENING_POLICIES.items():
            with self.subTest(run=run_id):
                self.assertEqual(checked_opening(self.frame(run_id, policies))["manifest"]["runId"], run_id)
        for key in additions:
            with self.subTest(key=key):
                for run_id, policies in ((self.old, {**previous, key: current[key]}),
                                         (self.new, {k:v for k,v in current.items() if k != key}),
                                         (self.new, {**current, key: "unregistered"})):
                    with self.assertRaises(ValueError):
                        checked_opening(self.frame(run_id, policies))

    def test_manifest_must_belong_to_its_frame_and_match_the_confirmed_command(self):
        policies = OPENING_POLICIES[self.new]
        with self.assertRaises(ValueError):
            checked_opening(self.frame(self.new, policies, self.old))
        frame = self.frame(self.new, policies)
        frame["events"][0]["payload"] = self.frame(self.old, OPENING_POLICIES[self.old])["commandContent"]
        with self.assertRaises(ValueError):
            checked_opening(frame)


class RepeatedReadingTest(unittest.TestCase):
    def test_reread_requires_the_original_observation_and_a_new_matching_completed_task(self):
        seen = {}
        first = {"readId": "READ-9", "personPartyId": "kate", "docId": "research-report",
                 "version": 1, "completedAt": 100, "body": "original"}
        raw = json.dumps(first)
        self.assertFalse(checked_reread({}, raw, first, 100, seen))
        task = {"readId": "READ-33", "personPartyId": "kate", "docId": "research-report",
                "version": 1, "commandId": "repeat"}
        receipt = {"taskId": "read:READ-33", "commandId": "repeat", "kind": "INFORMATION_COMPLETE_READ_V1",
                   "accepted": True, "reason": "COMPLETED", "subjectId": "READ-33", "readerPartyId": "kate",
                   "timeMillis": 200, "position": 87}

        def frame(t, r):
            return {"position": 87, "result": "COMPLETED", "commandId": "task:read:READ-33", "events": [
                    {"type": "TASK_COMPLETED_V1", "payload": json.dumps(t)},
                    {"type": "INFORMATION_REPORT_V1", "payload": json.dumps(r)}]}

        valid = frame(task, receipt)
        altered = [frame({**task,k:v}, receipt) for k,v in
                   (("personPartyId","other"),("docId","other-report"),("version",2),("readId","READ-34"))]
        altered += [frame(task, {**receipt,k:v}) for k,v in
                    (("accepted",False),("commandId","other"),("timeMillis",199),("position",88))]
        missing = deepcopy(valid)
        missing["events"].pop()
        for wrong in [*altered, missing]:
            with self.assertRaises(ValueError):
                checked_reread(wrong, raw, first, 200, seen)
        with self.assertRaises(ValueError):
            checked_reread(valid, raw, first, 200, {})
        with self.assertRaises(ValueError):
            checked_reread(valid, json.dumps({**first,"body":"changed"}), first, 200, seen)
        self.assertTrue(checked_reread(valid, raw, first, 200, seen))
        with self.assertRaises(ValueError):
            checked_reread(valid, raw, first, 200, seen)


if __name__ == "__main__":
    unittest.main()
