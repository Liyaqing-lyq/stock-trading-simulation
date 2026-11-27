"""标准十公司审计共享的版本身份、清单事件和十一版增量规则回归。"""
import json
import unittest

from audit_ten_company_run import OPENING_POLICIES, checked_opening


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


if __name__ == "__main__":
    unittest.main()
