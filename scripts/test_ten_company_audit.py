"""标准十公司审计共享的版本身份、清单事件和增量规则回归。"""
import json
import unittest
from copy import deepcopy

from audit_ten_company_run import OPENING_POLICIES, checked_amendment_notice, checked_dividend, checked_loan_loss, checked_loan_receipt, checked_opening, checked_reread


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


    def test_v14_rejects_missing_changed_or_backdated_followup_policies(self):
        previous, run = "web-ten-company-autonomy-v13", "web-ten-company-autonomy-v14"
        added = {"media-withdrawal": "published-version-notice-v1",
                 "media-work-calendar": "finite-business-shifts-v1",
                 "personal-attention": "weekday-duty-windows-v1",
                 "dividend-claims": "holder-and-issuer-followup-v1"}
        current = {**OPENING_POLICIES[previous], **added}
        checked_opening(self.frame(run, current))
        for key, value in added.items():
            for version, wrong in ((previous, {**OPENING_POLICIES[previous], key: value}),
                                   (run, {k: v for k, v in current.items() if k != key}),
                                   (run, {**current, key: "unknown"})):
                with self.subTest(version=version, key=key), self.assertRaises(ValueError):
                    checked_opening(self.frame(version, wrong))


    def test_v20_bank_and_board_arguments_require_the_new_opening(self):
        previous, current = "web-ten-company-autonomy-v19", "web-ten-company-autonomy-v20"
        old = OPENING_POLICIES[previous]
        added = {"crisis-action-arguments": "operation-specific-line-identity-v1",
                 "board-research-arguments": "action-and-evidence-reference-v1"}
        new = {**old, **added}
        checked_opening(self.frame(previous, old))
        checked_opening(self.frame(current, new))
        for key, value in added.items():
            for run, invalid in ((previous, {**old, key: value}),
                                 (current, {k: v for k, v in new.items() if k != key}),
                                 (current, {**new, key: "unknown"})):
                with self.subTest(run=run, key=key), self.assertRaises(ValueError):
                    checked_opening(self.frame(run, invalid))

    def test_v19_onsite_clock_and_catalog_belong_to_their_opening(self):
        previous, run = "web-ten-company-autonomy-v18", "web-ten-company-autonomy-v19"
        old = OPENING_POLICIES[previous]
        added = {"onsite-decision-clock": "observed-action-completion-v1",
                 "personal-discovery-catalog": "bounded-catalog-search-pages-v1",
                 "personal-participant-history": "bounded-personal-history-v1"}
        current = {**old, **added}
        checked_opening(self.frame(run, current))
        for key, value in added.items():
            for version, wrong in ((previous, {**old, key: value}),
                                   (run, {k: v for k, v in current.items() if k != key}),
                                   (run, {**current, key: "unknown"})):
                with self.subTest(version=version, key=key), self.assertRaises(ValueError):
                    checked_opening(self.frame(version, wrong))

    def test_v18_dividend_amendments_is_bound_to_its_opening(self):
        previous, current = "web-ten-company-autonomy-v17", "web-ten-company-autonomy-v18"
        old = OPENING_POLICIES[previous]
        new = {**old, "issuer-dividend-amendments": "board-correction-before-record-v1"}
        checked_opening(self.frame(current, new))
        for run, invalid in ((previous, new), (current, old), (current, {**new, "issuer-dividend-amendments": "unknown"})):
            with self.subTest(run=run), self.assertRaises(ValueError):
                checked_opening(self.frame(run, invalid))

    def test_v17_interview_timing_is_bound_to_its_opening(self):
        previous, current = "web-ten-company-autonomy-v16", "web-ten-company-autonomy-v17"
        old = OPENING_POLICIES[previous]
        new = {**old, "interview-time-contract": "effective-window-reading-floor-v1"}
        checked_opening(self.frame(current, new))
        for run, invalid in ((previous, new), (current, old), (current, {**new, "interview-time-contract": "unknown"})):
            with self.subTest(run=run), self.assertRaises(ValueError):
                checked_opening(self.frame(run, invalid))

    def test_v16_belief_contract_is_bound_to_its_opening(self):
        previous, current = "web-ten-company-autonomy-v15", "web-ten-company-autonomy-v16"
        old = OPENING_POLICIES[previous]
        new = {**old, "personal-belief-contract": "visible-orders-evidence-v1"}
        checked_opening(self.frame(current, new))
        for run, invalid in ((previous, new), (current, old), (current, {**new, "personal-belief-contract": "unknown"})):
            with self.subTest(run=run), self.assertRaises(ValueError):
                checked_opening(self.frame(run, invalid))

    def test_v15_loan_and_trade_policies_are_bound_to_their_opening(self):
        previous, current = "web-ten-company-autonomy-v14", "web-ten-company-autonomy-v15"
        old = OPENING_POLICIES[previous]
        new = {**old, "bank-loan-loss": "manager-impair-writeoff-v1",
               "material-information-trading": "issuer-disclosure-window-v1",
               "customer-personal-tools": "personal-communication-v1"}
        checked_opening(self.frame(current, new))
        for key in new.keys() - old.keys():
            for run, changed in ((previous, {**old, key: new[key]}),
                                 (current, {k: v for k, v in new.items() if k != key}),
                                 (current, {**new, key: "unregistered"})):
                with self.subTest(run=run, key=key), self.assertRaises(ValueError):
                    checked_opening(self.frame(run, changed))


class LoanLossEvidenceTest(unittest.TestCase):
    profile = {"buckets": {"firm": {"loanGl": "loan", "llpGl": "provision"}},
               "parties": {"firm": {"depositGl": "deposit"}}}

    @staticmethod
    def state(face, onbook, memo, cash, provision, expense, recovered):
        tranche = {"trancheId": "T", "contract": face, "onbook": onbook, "writtenOff": memo}
        return {"glByBank": {"north": {"loan": onbook, "deposit": -cash, "provision": -provision,
                                       "IMPAIR": expense, "RECOVERY": -recovered, "RESERVE": 300}},
                "loans": {"L|5:north|4:firm": {"tranches": [tranche]}}}

    def check(self, kind, amount, receipt, before, after):
        request = {"kind": kind, "bankKey": "north", "customerKey": "firm", "loanId": "original", "amount": amount}
        contracts = {"original": {"bankKey": "north", "borrowerKey": "firm", "trancheId": "T"}}
        return checked_loan_loss(kind, request, receipt, before, after, self.profile, contracts)

    def test_writeoff_preserves_the_borrowers_debt_and_only_repayment_moves_the_deposit(self):
        orig = self.state(200, 200, 0, 90, 0, 0, 0)
        provisioned = self.state(200, 200, 0, 90, 30, 30, 0)
        written = self.state(200, 185, 15, 90, 15, 30, 0)
        paid = self.state(180, 180, 0, 70, 15, 30, 15)
        self.assertEqual(30, self.check("LOAN_IMPAIR", 30, "amount=30;bucket=firm", orig, provisioned)["loanImpairmentAdded"])
        self.assertEqual(15, self.check("LOAN_WRITE_OFF", 15, "amount=15;tranche=T", provisioned, written)["loanPrincipalWrittenOff"])
        amounts = self.check("LOAN_REPAY", 20, "amount=20;tranche=T;recovery=15", written, paid)
        self.assertEqual((20, 15), (amounts["repaidPrincipal"], amounts["writtenOffPrincipalRecovered"]))
        for false in (self.state(185, 185, 0, 90, 15, 30, 0), self.state(200, 185, 15, 75, 15, 30, 0)):
            with self.assertRaises(ValueError):
                self.check("LOAN_WRITE_OFF", 15, "amount=15;tranche=T", provisioned, false)
        with self.assertRaises(ValueError):
            self.check("LOAN_REPAY", 20, "amount=20;tranche=T;recovery=20", written, paid)


    def test_retry_returns_the_original_result_and_adds_no_loss(self):
        original = self.state(200, 200, 0, 90, 0, 0, 0)
        booked = self.state(200, 200, 0, 90, 30, 30, 0)
        contracts = {"c": {"bankKey": "north", "borrowerKey": "firm", "trancheId": "T"}}
        seen = {}

        def sample(position, receipt, before, after):
            req = {"requestId": "request", "kind": "LOAN_IMPAIR", "bankKey": "north", "customerKey": "firm", "loanId": "c", "amount": 30}
            task = {"commandId": str(position), "personPartyId": "manager", "requestJson": json.dumps(req)}
            result = {**req, "taskId": str(position), "position": position, "atMillis": position, "commandId": str(position),
                      "personPartyId": "manager", "accepted": True, "reason": "ACCEPTED", "receipt": receipt}
            frame = {"position": position, "events": [{"type": "TASK_COMPLETED_V1", "eventId": "task-completed:" + str(position),
                                                        "payload": json.dumps(task)}]}
            return checked_loan_receipt(frame, result, before, after, self.profile, contracts, True, seen)

        proof = "amount=30;bucket=firm"
        self.assertEqual(30, sample(3, proof, original, booked)[1]["loanImpairmentAdded"])
        entry, flows = sample(7, proof + ";replayed=true", booked, booked)
        self.assertEqual((3, {}), (entry["originalPosition"], flows))
        with self.assertRaises(ValueError):
            sample(8, proof, booked, booked)
        with self.assertRaises(ValueError):
            sample(9, proof + ";replayed=true", original, booked)


class DividendEvidenceTest(unittest.TestCase):
    def test_dividend_amendments_require_a_matching_proposal_and_audit_receipt(self):
        original = {"schemeId":"old","symbol":"ENG","issuerOwnerId":"company","companyAccountId":"issuer",
                    "perShare":2,"recordAtMillis":100,"exAtMillis":200,"payAtMillis":300,
                    "phase":"AUTHORIZED","authorizationRef":"original-approval","cancelReason":""}
        cash = {"issuer":10}
        def frame(kind, reason, request, position):
            task = {"commandId":"c","schemeId":"next" if request else "","meetingId":"m","request":request}
            report = {"taskId":"t","kind":kind,"commandId":"c","meetingId":"m","position":position,
                      "atMillis":position,"schemeId":"old","accepted":True,"reason":reason}
            payload = json.dumps(task)
            return {"runId":"web-ten-company-autonomy-v18","position":position,"commandContent":payload,
                    "result":reason if not request else "ACCEPTED","events":[
                        {"eventId":"task-completed:t","type":"TASK_COMPLETED_V1","payload":payload},
                        {"eventId":"dividend-report:t","type":"DIVIDEND_REPORT_V1","payload":json.dumps(report)}]}
        for operation, completed in (("CANCEL","CANCELLED"),("REPLACE","REPLACED")):
            with self.subTest(operation=operation):
                book = {"schemes":{"old":original.copy()},"snapshots":{},"claims":{},"payments":[],"receipts":{}}
                ctx = {}
                amendment = {"currentSchemeId":"old","expectedVersion":1,"operation":operation,"reason":"cash revision"}
                new = {k:v for k,v in original.items() if k not in ("phase","authorizationRef","cancelReason")}
                new.update(schemeId="next",perShare=1 if operation=="REPLACE" else original["perShare"])
                offer = {**new,"amendment":amendment}
                proposed = frame("GOVERNANCE_DIVIDEND_PROPOSAL_V1","AMENDMENT_PROPOSED",offer,10)
                self.assertEqual(checked_dividend(proposed,10,book,cash,cash,"ENG",followup=ctx)["stage"],
                                 "AMENDMENT_PROPOSED")
                old_mode = {**proposed,"runId":"web-ten-company-autonomy-v17"}
                with self.assertRaises(ValueError):
                    checked_dividend(old_mode,10,book,cash,cash,"ENG",followup=ctx)
                book["schemes"]["old"].update(phase="CANCELLED",cancelReason=amendment["reason"])
                applied = frame("GOVERNANCE_DIVIDEND_IMPLEMENT_V1",completed,None,20)
                notice = json.dumps({"docId":"dividend-public:old","commandId":"div-publish:next","audienceKind":"PUBLIC","body":"revised"})
                child = {"kind":"INFORMATION_PUBLISH_VERSION_V1","taskId":"notice","dueTimeMillis":21,"payload":notice}
                applied["events"].append({"eventId":"task-scheduled:notice","type":"TASK_SCHEDULED_V1","payload":json.dumps(child)})
                request = {**amendment,"authorizationRef":"m-approved","replacement":new if operation=="REPLACE" else None}
                if operation=="REPLACE":
                    book["schemes"]["next"] = {**new,"phase":"AUTHORIZED","authorizationRef":"m-approved","cancelReason":""}
                receipt = {"eventId":"div-amend:m","position":20,"atMillis":20,"action":"AMEND","requestJson":json.dumps(request)}
                book["receipts"]["div-amend:m"] = receipt
                actual = checked_dividend(applied,20,book,cash,cash,"ENG",followup=ctx)
                self.assertEqual(actual["stage"],completed)
                self.assertNotIn("cashPaid",actual)
                self.assertEqual(actual["noticeScheduledAtMillis"],21)
                info = {"taskId":"notice","kind":child["kind"],"accepted":True,"subjectId":"dividend-public:old",
                        "commandId":"div-publish:next","position":21,"timeMillis":21,"reason":"OK"}
                publication = {"position":21,"commandContent":notice,"result":"OK","events":[
                    {"type":"TASK_COMPLETED_V1","eventId":"task-completed:notice","payload":notice},
                    {"type":"INFORMATION_REPORT_V1","eventId":"information-report:notice","payload":json.dumps(info)}]}
                with self.assertRaises(ValueError):
                    checked_amendment_notice({"events":[]},22,ctx)
                self.assertEqual(checked_amendment_notice(publication,21,ctx)["stage"],"REANNOUNCED")

                with self.assertRaises(ValueError):
                    checked_dividend(applied,20,book,cash,{"issuer":9},"ENG",followup=ctx)
                receipt["requestJson"] = json.dumps({**request,"expectedVersion":2})
                with self.assertRaises(ValueError):
                    checked_dividend(applied,20,book,cash,cash,"ENG",followup=ctx)
                receipt["requestJson"] = json.dumps(request)
                book["schemes"]["old"]["perShare"] = 3
                with self.assertRaises(ValueError):
                    checked_dividend(applied,20,book,cash,cash,"ENG",followup=ctx)
                book["schemes"]["old"]["perShare"] = 2
                expired_frame = frame("DIVIDEND_SETTLE_V1","INVALID_PHASE",None,300)
                expired_frame["result"] = "REJECTED"
                task = {"commandId":"div-pay:old","schemeId":"old","meetingId":"","request":original}
                expired_frame["events"][0]["payload"] = expired_frame["commandContent"] = json.dumps(task)
                rejected = json.loads(expired_frame["events"][1]["payload"])
                rejected.update(accepted=False,meetingId="",commandId="div-pay:old")
                expired_frame["events"][1]["payload"] = json.dumps(rejected)
                ctx.update(scheduled={},failures={})
                self.assertTrue(checked_dividend(expired_frame,300,book,cash,cash,"ENG",followup=ctx)["retiredBeforeRecord"])
                self.assertEqual(ctx["failures"], {})
                book["claims"]["early"] = {"schemeId":"old"}
                with self.assertRaises(ValueError):
                    checked_dividend(applied,20,book,cash,cash,"ENG",followup=ctx)

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


class DividendClaimAuditTest(unittest.TestCase):
    def test_retry_requires_issuer_chair_and_releases_only_the_previously_booked_amount(self):
        prior = {"a":{"claimId":"a","schemeId":"s","accountId":"A","face":4,"paid":0,"impairment":2,"quantity":1}}
        failure = "dividend-report:unpaid"
        proof = {"schemeId":"s","companyAccountId":"issuer","total":4,"legs":[{"claimId":"a","accountId":"A","amount":4}]}
        def pay(person="alice", release=2, evidence=True):
            task = {"commandId":"c","meetingId":"","schemeId":"s","seatId":"company-owner","decisionWork":{"person":person,"duration":1000}}
            raw = json.dumps(task)
            report = {"taskId":"t","kind":"DIVIDEND_SETTLE_V1","commandId":"c","meetingId":"","position":20,
                      "atMillis":50,"schemeId":"s","accepted":True,"reason":"PAID"}
            events = [{"type":"TASK_COMPLETED_V1","eventId":"task-completed:t","payload":raw},
                      {"type":"DIVIDEND_REPORT_V1","eventId":"dividend-report:t","payload":json.dumps(report)},
                      {"type":"DIVIDEND_CASH_PAYMENT_V1","eventId":"dividend-cash:s","payload":json.dumps(proof)}]
            book = {"schemes":{"s":{"phase":"SETTLED","symbol":"ENG","issuerOwnerId":"company-owner","companyAccountId":"issuer",
                                    "perShare":4,"recordAtMillis":20}},
                    "claims":{"a":{**prior["a"],"paid":4,"impairment":0}},
                    "snapshots":{"s":{"schemeId":"s","recordAtMillis":20,"totalQuantity":1,"totalAmount":4,
                                      "entries":[{"accountId":"A","quantity":1,"face":4}]}},
                    "payments":[{"schemeId":"s","position":20,"cashEvidenceId":"dividend-cash:s","atMillis":50,
                                 "perClaim":{"a":4},"amount":4,"impairmentReleased":{"a":release}}]}
            context = {"chair":"alice","scheduled":{"t":task},"failures":{"s":failure} if evidence else {}}
            return checked_dividend({"position":20,"result":"ACCEPTED","commandContent":raw,"events":events},50,book,
                                    {"issuer":6,"A":1},{"issuer":2,"A":5},"ENG",prior,context)
        actual = pay()
        self.assertEqual((actual["personPartyId"],actual["cashPaid"],actual["claimImpairmentReleased"]),("alice",4,2))
        for broken in ({"person":"manager"},{"release":0},{"release":4},{"evidence":False}):
            with self.subTest(broken=broken), self.assertRaises(ValueError):
                pay(**broken)

    def test_holder_impairment_requires_original_failure_and_changes_only_the_personal_claim(self):
        old = {"claimId":"a","schemeId":"s","accountId":"A","face":100,"paid":0,"impairment":4}
        prior = {"a":old}
        task = {"commandId":"c","meetingId":"","claimId":"a","amount":6,"evidenceRef":"dividend-report:failed",
                "seatId":"owner-a","schemeId":"","decisionWork":{"person":"nina","duration":1000}}
        record = {"impairmentId":"div-impair:t","position":19,"claimId":"a","atMillis":42,
                  "amount":6,"evidenceRef":"dividend-report:failed"}
        cash = {"issuer":1,"A":1}

        def read(*, actor="nina", proof=None, entered=10, face=100, authority=True, failure="dividend-report:failed"):
            source = {**task,"decisionWork":{"person":actor,"duration":1000}}
            raw = json.dumps(source)
            receipt = {"taskId":"t","kind":"DIVIDEND_IMPAIR_V1","commandId":"c","meetingId":"","position":19,
                       "atMillis":42,"schemeId":"s","accepted":True,"reason":"OK"}
            events = [{"type":"TASK_COMPLETED_V1","eventId":"task-completed:t","payload":raw},
                      {"type":"DIVIDEND_REPORT_V1","eventId":"dividend-report:t","payload":json.dumps(receipt)}]
            book = {"schemes":{"s":{"symbol":"ENG","phase":"EX_BOOKED","companyAccountId":"issuer","perShare":5}},
                    "claims":{"a":{**old,"impairment":entered,"face":face}},"impairments":[proof or record]}
            context = {"holders":{"A":("owner-a","nina")},"scheduled":{"t":source} if authority else {},
                       "failures":{"s":failure}}
            return checked_dividend({"position":19,"result":"ACCEPTED","commandContent":raw,"events":events},
                                    42,book,cash,cash,"ENG",prior,context)

        ok = read()
        self.assertEqual((ok["claimImpairmentAdded"],ok["holderNetReceivable"],ok["issuerFaceStillUnpaid"]),(6,90,100))
        self.assertNotIn("cashPaid",ok)
        for broken in ({"actor":"fund-manager"},{"authority":False},{"failure":"other-failure"},{"entered":6},{"face":94},
                       {"proof":{**record,"claimId":"b"}},{"proof":{**record,"amount":7}},{"proof":{**record,"position":20}}):
            with self.subTest(broken=broken),self.assertRaises(ValueError):
                read(**broken)


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
