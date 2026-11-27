"""标准十公司审计共享的版本身份、清单事件和增量规则回归。"""
import json
import unittest
from copy import deepcopy

from audit_ten_company_run import OPENING_POLICIES, checked_dividend, checked_opening, checked_reread


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

    def test_interview_autonomy_is_the_only_additional_v12_policy(self):
        run = "web-ten-company-autonomy-v12"
        previous = OPENING_POLICIES[self.new]
        current = {**previous, "interview-autonomy": "active-session-turn-v1"}
        self.assertEqual(OPENING_POLICIES[run], current)
        checked_opening(self.frame(run, current))
        for version, policies in ((self.new, current), (run, previous),
                                  (run, {**current, "interview-autonomy": "unregistered"}),
                                  (run, {k: v for k, v in current.items() if k != "pharma-licensing"})):
            with self.subTest(run=version), self.assertRaises(ValueError):
                checked_opening(self.frame(version, policies))

    def test_v13_requires_both_issuer_decisions_and_personal_market_orders(self):
        previous_run, run = "web-ten-company-autonomy-v12", "web-ten-company-autonomy-v13"
        previous = OPENING_POLICIES[previous_run]
        current = {**previous, "issuer-decisions": "engineering-cash-dividend-v1",
                   "personal-market-orders": "immediate-unfilled-cancel-v1"}
        self.assertEqual(OPENING_POLICIES[run], current)
        checked_opening(self.frame(run, current))
        for key in ("issuer-decisions", "personal-market-orders"):
            for version, policies in ((previous_run, {**previous, key: current[key]}),
                                      (run, {k: v for k, v in current.items() if k != key}),
                                      (run, {**current, key: "unregistered"})):
                with self.subTest(run=version, key=key), self.assertRaises(ValueError):
                    checked_opening(self.frame(version, policies))
        with self.assertRaises(ValueError):
            checked_opening(self.frame(run, {k: v for k, v in current.items() if k != "interview-autonomy"}))


class DividendEvidenceTest(unittest.TestCase):
    def test_registration_booking_and_cash_legs_are_separate(self):
        before = {"issuer": 10, "a": 2, "b": 0}
        after = {"issuer": 4, "a": 6, "b": 2}
        rights = [{"accountId": "a", "quantity": 2, "face": 4}, {"accountId": "b", "quantity": 1, "face": 2}]
        terms = {"phase": "REGISTERED", "symbol": "ENG", "perShare": 2, "companyAccountId": "issuer",
                 "recordAtMillis": 100, "exAtMillis": 200}
        book = {"schemes": {"s": terms}, "claims": {}, "payments": [],
                "snapshots": {"s": {"schemeId": "s", "recordAtMillis": 100, "entries": rights,
                                     "totalQuantity": 3, "totalAmount": 6}}}

        def frame(kind, now, *, accepted=True, proof=None):
            task = json.dumps({"commandId": "cmd", "schemeId": "s", "meetingId": ""})
            report = {"taskId": "t", "kind": kind, "commandId": "cmd", "meetingId": "", "position": 19,
                      "atMillis": now, "schemeId": "s", "accepted": accepted, "reason": "OK" if accepted else "INSUFFICIENT_CASH"}
            events = [{"eventId": "task-completed:t", "type": "TASK_COMPLETED_V1", "payload": task},
                      {"eventId": "dividend-report:t", "type": "DIVIDEND_REPORT_V1", "payload": json.dumps(report)}]
            if proof is not None:
                events.append({"eventId": "dividend-cash:s", "type": "DIVIDEND_CASH_PAYMENT_V1", "payload": json.dumps(proof)})
            return {"position": 19, "commandContent": task, "result": "ACCEPTED" if accepted else "REJECTED", "events": events}

        registration = frame("DIVIDEND_REGISTER_V1",100)
        registered = checked_dividend(registration,100,book,before,before,"ENG")
        self.assertEqual(registered["registeredFace"],6)
        self.assertNotIn("receivablesBooked",registered)
        self.assertNotIn("cashPaid",registered)
        book["claims"] = {r["accountId"]: {**r,"schemeId": "s", "paid": 0} for r in rights}
        with self.assertRaises(ValueError):
            checked_dividend(registration,100,book,before,before,"ENG")
        terms["phase"] = "EX_BOOKED"
        booked = checked_dividend(frame("DIVIDEND_BOOK_EX_V1",200),200,book,before,before,"ENG")
        self.assertEqual(booked["receivablesBooked"],6)
        self.assertNotIn("cashPaid",booked)
        insufficient = frame("DIVIDEND_SETTLE_V1",300,accepted=False)
        failed = checked_dividend(insufficient,300,book,before,before,"ENG")
        self.assertEqual((failed["stage"],failed["attemptedStage"]),("REJECTED","SETTLED"))
        self.assertEqual(failed["holderFaceStillUnpaid"],6)
        self.assertNotIn("cashPaid",failed)
        with self.assertRaises(ValueError):
            checked_dividend(insufficient,300,book,before,after,"ENG")

        terms["phase"] = "SETTLED"
        for claim in book["claims"].values():
            claim["paid"] = claim["face"]
        book["payments"] = [{"schemeId": "s", "position": 19, "atMillis": 300, "cashEvidenceId": "dividend-cash:s",
                             "perClaim": {"a": 4, "b": 2}, "amount": 6}]
        proof = {"schemeId": "s", "companyAccountId": "issuer", "total": 6,
                 "legs": [{"claimId": r["accountId"], "accountId": r["accountId"], "amount": r["face"]} for r in rights]}
        paid = frame("DIVIDEND_SETTLE_V1",300,proof=proof)
        self.assertEqual(checked_dividend(paid,300,book,before,after,"ENG")["cashPaid"],6)
        with self.assertRaises(ValueError):
            checked_dividend(paid,300,book,before,{"issuer":4,"a":4,"b":4},"ENG")
        for wrong in (None, {**proof,"companyAccountId":"b"}, {**proof,"legs":proof["legs"][:1]}):
            with self.subTest(proof=wrong), self.assertRaises(ValueError):
                checked_dividend(frame("DIVIDEND_SETTLE_V1",300,proof=wrong),300,book,before,after,"ENG")


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
