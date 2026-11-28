"""只读复算十公司普通紧凑日志中的现金、成交、阅读与决定回执，按实验日输出。"""
import argparse
from collections import Counter
from decimal import Decimal
import gzip
import hashlib
import json
from pathlib import Path
import struct

DAY = 86_400_000
FIELDS = ("accounts", "timeMillis")
READ = "INFORMATION_OBSERVATION_V1"
TRADE = "EXCHANGE_TRADE_V1"
ORDER = "EXCHANGE_ORDER_REPORT_V1"


READING_POLICY = {"document-reading-clock": "body-pacing-300-v1"}
GOVERNANCE_POLICIES = {**READING_POLICY, "editorial-template-guide": "office-source-map-keys-v1",
                       "issuer-period-metrics": "operating-evidence-v1"}
DRAFT_POLICIES = {**GOVERNANCE_POLICIES, "editorial-draft-visibility": "submitted-document-v1"}
PERSONAL_POLICIES = {**DRAFT_POLICIES, "crisis-office-objectives": "active-appointments-v1",
                     "personal-note-contract": "explicit-note-reference-v1",
                     "decision-envelope-guide": "typed-review-fields-v1"}
INTERVIEW_POLICIES = {**PERSONAL_POLICIES, "interview-action-feedback": "bound-coordination-v1",
                      "social-action-feedback": "bound-message-v1", "periodic-action-feedback": "bound-period-result-v1"}
MEETING_POLICIES = {**INTERVIEW_POLICIES, "periodic-meeting-view": "readable-period-material-v1"}
DIGITAL_POLICIES = {**MEETING_POLICIES, "digital-cessation": "board-wind-down-v1"}
FEEDBACK_POLICIES = {**DIGITAL_POLICIES, "editorial-review-feedback": "private-revision-reading-v1",
                     "interview-window-basis": "execution-clock-v1", "web-person-takeover": "marked-branch-v1"}
PERSONAL_NEWS_POLICIES = {**FEEDBACK_POLICIES, "personal-news-ranking": "visible-interest-v1",
                          "news-exploration-slot": "4", "market-session-wake": "role-exposure-choice-v1",
                          "media-subscriptions": "consented-interview-delivery-v1", "cross-border-editing": "dual-clock-editing-v1",
                          "pharma-licensing": "approved-use-contract-v1", "hydro-inflow-path": "finite-1080d-v1"}
INTERVIEW_AUTONOMY_POLICIES = {**PERSONAL_NEWS_POLICIES, "interview-autonomy": "active-session-turn-v1"}
ISSUER_DECISIONS_POLICIES = {**INTERVIEW_AUTONOMY_POLICIES, "issuer-decisions": "engineering-cash-dividend-v1",
                            "personal-market-orders": "immediate-unfilled-cancel-v1"}
MEDIA_FOLLOWUP_POLICIES = {**ISSUER_DECISIONS_POLICIES, "media-withdrawal": "published-version-notice-v1",
                          "media-work-calendar": "finite-business-shifts-v1",
                          "personal-attention": "weekday-duty-windows-v1",
                          "dividend-claims": "holder-and-issuer-followup-v1"}
OPENING_POLICIES = {"web-ten-company-autonomy-v2": {}, "web-ten-company-autonomy-v3": READING_POLICY,
                    "web-ten-company-autonomy-v4": GOVERNANCE_POLICIES, "web-ten-company-autonomy-v5": DRAFT_POLICIES,
                    "web-ten-company-autonomy-v6": PERSONAL_POLICIES, "web-ten-company-autonomy-v7": INTERVIEW_POLICIES,
                    "web-ten-company-autonomy-v8": MEETING_POLICIES, "web-ten-company-autonomy-v9": DIGITAL_POLICIES,
                    "web-ten-company-autonomy-v10": FEEDBACK_POLICIES,
                    "web-ten-company-autonomy-v11": PERSONAL_NEWS_POLICIES,
                    "web-ten-company-autonomy-v12": INTERVIEW_AUTONOMY_POLICIES,
                    "web-ten-company-autonomy-v13": ISSUER_DECISIONS_POLICIES,
                    "web-ten-company-autonomy-v14": MEDIA_FOLLOWUP_POLICIES}


def check_policies(run_id, data_versions):
    expected = OPENING_POLICIES[run_id]
    actual = {k: data_versions[k] for k in MEDIA_FOLLOWUP_POLICIES if k in data_versions}
    if data_versions.get("branch-profile-run") != run_id or actual != expected:
        raise ValueError("原生开局声明的政策与该版运行身份不符")


def decode(raw):
    return json.loads(raw, parse_float=Decimal)


def checked_opening(frame):
    """三种审计均依据已确认开局命令、唯一清单事件及该版冻结策略。"""
    run_id = frame["runId"]
    records = [e["payload"] for e in frame["events"] if e["type"] == "RUN_MANIFEST_V1"]
    if run_id not in OPENING_POLICIES or frame["position"] != 1 or records != [frame["commandContent"]]:
        raise ValueError("标准十公司首帧清单与已确认开局命令不符")
    command = decode(records[0])
    if command["manifest"]["runId"] != run_id:
        raise ValueError("首帧运行与清单身份不符")
    check_policies(run_id, command["manifest"]["dataVersions"])
    return command


def checked_frames(source, file_digest):
    previous = 0
    with source.open("rb") as stream:
        while header := stream.read(8):
            if len(header) != 8:
                raise ValueError("未封存的帧头")
            length, check = struct.unpack(">ii", header)
            if check != ~length:
                raise ValueError("帧头校验失败")
            payload_length = length & 0x7fffffff
            payload = stream.read(payload_length)
            checksum = stream.read(32)
            if len(payload) != payload_length or hashlib.sha256(payload).digest() != checksum:
                raise ValueError("帧未完整或内容摘要不符")
            file_digest.update(header)
            file_digest.update(payload)
            file_digest.update(checksum)
            frame = decode(gzip.decompress(payload) if length < 0 else payload)
            if previous == 0 and frame.get("commandId") == "__branch__":
                origin = decode(frame["commandContent"])["branch"]
                if origin["kind"] == "GOD_TAKEOVER":
                    raise ValueError("人工接管分支应单列，不计入普通全员自然样本")
                raise ValueError("该档从父运行的确认点派生，需使用其封存父前缀复核分支")
            if frame["position"] != previous + 1:
                raise ValueError("需要从开局起连续的完整日志")
            previous += 1
            yield frame


def patch_field(before, patch):
    kind = patch["kind"]
    if kind == "same":
        return before
    if kind == "replace":
        return patch["value"]
    if kind == "array" and isinstance(before, list):
        length = patch["length"]
        if not 0 <= length <= len(before) + len(patch["changes"]):
            raise ValueError("数组差量长度不合法")
        if any(str(i) not in patch["changes"] for i in range(len(before), length)):
            raise ValueError("新增订单数组缺少对应值")
        after = before[:length] + [None] * max(0, length - len(before))
        for key, change in patch["changes"].items():
            index = int(key)
            if str(index) != key or not 0 <= index < length:
                raise ValueError("数组差量下标越界")
            after[index] = patch_field(after[index], change)
        return after
    if kind != "object" or not isinstance(before, dict):
        raise ValueError("状态差量类型错误")
    for key in patch["removed"]:
        del before[key]
    for key, change in patch["changes"].items():
        before[key] = patch_field(before.get(key), change)
    return before


def opening_people(registry, accounts, lots):
    persons = {p["partyId"] for p in registry["parties"] if p["kind"] == "PERSON"}
    # 与标准十公司 WorldRuntime.systemPublisherOnly 相同。
    publishers = persons & {"research-system"}
    owners = {o["ownerId"]: o["partyId"] for o in registry["owners"] if o["kind"] == "PERSON"}
    portfolios = {}
    natural = persons - publishers
    for account_id, account in accounts.items():
        owner = owners.get(account["ownerId"])
        if owner not in natural:
            continue
        owned = [lot for lot in lots if lot["accountId"] == account_id and lot["remaining"] > 0]
        if account["cash"] == 0 and not owned:
            continue
        shares = Counter()
        for lot in owned:
            shares[lot["symbol"]] += lot["remaining"]
        row = portfolios.setdefault(owner, {})
        row[account_id] = {"cash": account["cash"], "source": account["source"],
                           "shares": shares,
                           "shareCost": sum((lot["remaining"] * lot["costPrice"] for lot in owned), Decimal(0))}
    return {"naturalPersons": sorted(natural), "systemPublishers": sorted(publishers),
            "initiallyFundedPersonalPortfolios": portfolios}


def closing_book(orders, symbols):
    book = {symbol: {"BUY": {}, "SELL": {}} for symbol in symbols}
    for order in orders:
        if order["status"] == "ACTIVE":
            remaining = order["quantity"] - order["filled"]
            if order["type"] != "LIMIT" or remaining <= 0:
                raise ValueError("簿上出现不能挂存的订单")
            levels = book[order["symbol"]][order["side"]]
            price = order["limitPrice"]
            levels[price] = levels.get(price, 0) + remaining
    close = {}
    for symbol, sides in book.items():
        bid = max(sides["BUY"], default=None)
        ask = min(sides["SELL"], default=None)
        close[symbol] = {"bestBid": bid, "bestBidQuantity": sides["BUY"].get(bid, 0),
                         "bestAsk": ask, "bestAskQuantity": sides["SELL"].get(ask, 0),
                         "totalBuyQuantity": sum(sides["BUY"].values()), "totalSellQuantity": sum(sides["SELL"].values())}
    return close


def check_trade_orders(trades, reports, orders):
    referenced = [trade_id for report in reports for trade_id in report["tradeIds"]]
    if Counter(t["tradeId"] for t in trades) != Counter(referenced) or len(set(referenced)) != len(referenced):
        raise ValueError("成交与同帧订单回报未逐笔对应")
    if not trades:
        return
    by_order = {order["orderId"]: order for order in orders}
    incoming = {trade_id: report["orderId"] for report in reports for trade_id in report["tradeIds"] if report["accepted"]}
    for t in trades:
        buy, sell = by_order[t["buyOrderId"]], by_order[t["sellOrderId"]]
        if (buy["side"] != "BUY" or sell["side"] != "SELL" or
                not buy["symbol"] == sell["symbol"] == t["symbol"] or
                buy["accountId"] != t["buyAccountId"] or sell["accountId"] != t["sellAccountId"]):
            raise ValueError("成交证券、方向或账户与原订单不符")
        maker, taker = sorted((buy, sell), key=lambda order: order["sequence"])
        if (incoming.get(t["tradeId"]) != taker["orderId"] or maker["sequence"] == taker["sequence"]
                or maker["type"] != "LIMIT" or maker["limitPrice"] != t["price"]):
            raise ValueError("成交与主动单回报或被动方挂价不符")


def checked_reread(frame, raw, observed, now, read_ids):
    """复读返回本人该文该版首次经验；另由本帧新完成的阅读任务和回执证明本次工时。"""
    first_id = observed["readId"]
    digest = hashlib.sha256(raw.encode("utf8")).hexdigest()
    if first_id not in read_ids and observed["completedAt"] == now:
        read_ids[first_id] = digest
        return False
    if read_ids.get(first_id) != digest or observed["completedAt"] > now:
        raise ValueError("阅读经验时间或原文与首次确认不符")
    tasks = [decode(e["payload"]) for e in frame["events"] if e["type"] == "TASK_COMPLETED_V1"]
    reports = [decode(e["payload"]) for e in frame["events"] if e["type"] == "INFORMATION_REPORT_V1"]
    if len(tasks) != 1 or len(reports) != 1:
        raise ValueError("复读缺少唯一的完成任务和信息回执")
    task, report = tasks[0], reports[0]
    new_id = task["readId"]
    task_id = "read:" + new_id
    if (new_id in read_ids or frame["commandId"] != "task:" + task_id or frame["result"] != "COMPLETED"
            or any(task[k] != observed[k] for k in ("personPartyId", "docId", "version"))
            or (report["kind"], report["taskId"], report["commandId"], report["subjectId"], report["readerPartyId"],
                report["accepted"], report["reason"], report["position"], report["timeMillis"]) !=
               ("INFORMATION_COMPLETE_READ_V1", task_id, task["commandId"], new_id, observed["personPartyId"],
                True, "COMPLETED", frame["position"], now)):
        raise ValueError("复读任务、读者、文号版本或完成时点与回执不符")
    read_ids[new_id] = None  # 重读任务已经计过，保留首次经验摘要。
    return True


DIVIDEND_STAGES = {
    "GOVERNANCE_DIVIDEND_PROPOSAL_V1": "PROPOSED", "GOVERNANCE_DIVIDEND_IMPLEMENT_V1": "AUTHORIZED",
    "DIVIDEND_REGISTER_V1": "REGISTERED", "DIVIDEND_BOOK_EX_V1": "EX_BOOKED",
    "DIVIDEND_SETTLE_V1": "SETTLED", "DIVIDEND_IMPAIR_V1": "IMPAIRMENT"
}


def dividend_board_receipt(frame, now, meetings):
    for event in frame["events"]:
        if event["type"] != "ENGINEERING_REPORT_V1":
            continue
        report = decode(event["payload"])
        if report["companyPartyId"] != "eng-a" or report["kind"] != "ENGINEERING_BOARD_V1":
            continue
        task = decode(frame["commandContent"])
        meeting, action = task["meetingId"], task["action"]
        if action == "CONVENE_DIVIDEND":
            if report["accepted"]:
                meetings.add(meeting)
        elif meeting not in meetings or action not in ("VOTE", "CLOSE"):
            continue
        if ((report["commandId"], report["timeMillis"],report["position"]) != (task["commandId"],now,frame["position"])
                or task["companyPartyId"] != "eng-a" or frame["result"] != ("ACCEPTED" if report["accepted"] else "REJECTED")
                or [e["payload"] for e in frame["events"] if e["type"] == "TASK_COMPLETED_V1"
                    and e["eventId"] == "task-completed:" + report["taskId"]] != [frame["commandContent"]]):
            raise ValueError("分红会议、表决任务或时点与业务回执不符")
        return {"issuer":"eng-a","stage":"BOARD_"+action,"meetingId":meeting,
                "personPartyId":task["personPartyId"],"seatId":task["seatId"],"vote":task["vote"],**report}
    return None


def dividend_followup_context(opening, registry):
    board = opening["engineering"]["boards"]["eng-a"]["board"]["charter"]
    chair, = [s["partyId"] for s in board["seats"] if s["seatId"] == board["chairSeatId"]]
    people = {r["ownerId"]:r["partyId"] for r in registry["owners"] if r["kind"] == "PERSON"}
    holders = {k:(v["ownerId"],people[v["ownerId"]]) for k,v in opening["accounts"].items() if v["ownerId"] in people}
    return {"chair":chair,"holders":holders,"failures":{},"scheduled":{}}


def track_dividend_decisions(events, context):
    for event in events:
        if event["type"] == "DECISION_ACTION_SCHEDULED_V1":
            task = decode(event["payload"])
            if task.get("decisionWork") and not task["meetingId"]:
                if not event["eventId"].startswith("dec-scheduled:"):
                    raise ValueError("分红跟进的决定任务标识不符")
                task_id = event["eventId"].removeprefix("dec-scheduled:")
                context["scheduled"][task_id] = task


def checked_followup(report, task, book, context):
    scheme = report["schemeId"]
    work = task.get("decisionWork")
    placed = context["scheduled"].pop(report["taskId"], None)
    if not work:
        if placed is not None:
            raise ValueError("原本人决定的分红任务缺少执行主体")
        return {}
    person = work["person"]
    if placed != task:
        raise ValueError("股利债权跟进缺少同一本人决定排程")
    if scheme not in context["failures"]:
        raise ValueError("股利债权跟进缺少原定付款日拒付凭据")
    if report["kind"] == "DIVIDEND_SETTLE_V1":
        if person != context["chair"] or task["seatId"] != book["schemes"][scheme]["issuerOwnerId"]:
            raise ValueError("补付并非由发行人主席沿原账户办理")
        return {"followup":"RETRY_PAYMENT", "personPartyId":person, "originalFailureRef":context["failures"][scheme]}
    claim = book["claims"].get(task["claimId"])
    if (claim is None or claim["schemeId"] != scheme or task["evidenceRef"] != context["failures"][scheme]
            or context["holders"].get(claim["accountId"]) != (task["seatId"], person)):
        raise ValueError("减值的债权、个人账户持有人或原始拒付凭据不符")
    return {"followup":"HOLDER_IMPAIRMENT", "personPartyId":person, "originalFailureRef":task["evidenceRef"]}


def checked_dividend(frame, now, book, previous_cash, cash, issuer_symbol, previous_claims=None, followup=None):
    rows = [e for e in frame["events"] if e["type"] == "DIVIDEND_REPORT_V1"]
    proofs = [e for e in frame["events"] if e["type"] == "DIVIDEND_CASH_PAYMENT_V1"]
    if not rows and not proofs:
        return None
    if len(rows) != 1:
        raise ValueError("分红实付或业务回执缺少唯一的分红任务")
    report = decode(rows[0]["payload"])
    stage = DIVIDEND_STAGES[report["kind"]]
    scheme = report["schemeId"]
    task_payloads = [e["payload"] for e in frame["events"] if e["type"] == "TASK_COMPLETED_V1"
                     and e["eventId"] == "task-completed:" + report["taskId"]]
    if (task_payloads != [frame["commandContent"]] or rows[0]["eventId"] != "dividend-report:" + report["taskId"]
            or (report["position"], report["atMillis"]) != (frame["position"], now)):
        raise ValueError("分红回执与已完成的任务、位置或时点不同")
    task = decode(task_payloads[0])
    if (task["commandId"] != report["commandId"] or task["meetingId"] != report["meetingId"]
            or (stage not in ("AUTHORIZED","IMPAIRMENT") and task["schemeId"] != scheme)):
        raise ValueError("分红决议、任务和方案标识不符")
    accepted = report["accepted"]
    if (frame["result"] != ("AUTHORIZED" if accepted and stage == "AUTHORIZED" else "ACCEPTED" if accepted else "REJECTED")
            or len(proofs) != int(accepted and stage == "SETTLED")):
        raise ValueError("分红结算标记与现金凭证数不符")
    row = {"issuer": "eng-a", "stage": stage, **report}
    if followup is not None and stage in ("SETTLED","IMPAIRMENT"):
        row.update(checked_followup(report,task,book,followup))
    if task.get("seatId") and stage in ("PROPOSED", "AUTHORIZED"):
        row["seatId"] = task["seatId"]
    if not proofs and cash != previous_cash:
        raise ValueError("未付款的分红任务发生现金变动")
    if not accepted:
        row.update(stage="REJECTED", attemptedStage=stage)
        if stage == "SETTLED" and scheme in book["schemes"]:
            row["holderFaceStillUnpaid"] = sum(c["face"]-c["paid"] for c in book["claims"].values() if c["schemeId"]==scheme)
            if (followup is not None and task.get("request") is not None and task["commandId"] == "div-pay:"+scheme
                    and now == book["schemes"][scheme]["payAtMillis"]):
                followup["failures"][scheme] = rows[0]["eventId"]
        return row
    terms = book["schemes"][scheme]
    if (terms["symbol"] != issuer_symbol or terms["companyAccountId"] not in cash
            or stage != "IMPAIRMENT" and terms["phase"] != stage):
        raise ValueError("发行证券或确认后的分红方案阶段不同")
    row.update(symbol=issuer_symbol, perShare=terms["perShare"], payerAccount=terms["companyAccountId"])
    if stage == "AUTHORIZED":
        row["authorizationRef"] = terms["authorizationRef"]
        if not row["authorizationRef"]:
            raise ValueError("已授权分红缺少实际决议引用")
    if stage == "IMPAIRMENT":
        claim_id = task["claimId"]
        claim = book["claims"][claim_id]
        increases = [i for i in book["impairments"] if i["position"] == frame["position"]]
        expected = {"impairmentId":"div-impair:"+report["taskId"],"position":frame["position"],"claimId":claim_id,
                    "atMillis":now,"amount":task["amount"],"evidenceRef":task["evidenceRef"]}
        if (increases != [expected] or claim["schemeId"] != scheme or terms["phase"] != "EX_BOOKED"
                or task["amount"] <= 0 or previous_claims is None or claim_id not in previous_claims
                or {**previous_claims,claim_id:{**previous_claims[claim_id],
                    "impairment":previous_claims[claim_id]["impairment"]+task["amount"]}} != book["claims"]
                or claim["impairment"] > claim["face"]-claim["paid"]):
            raise ValueError("减值记录与任务、证据、增提金额或原债权不符")
        return {**row,"claimId":claim_id,"accountId":claim["accountId"],"evidenceRef":task["evidenceRef"],
                "claimImpairmentAdded":task["amount"],"holderNetReceivable":claim["face"]-claim["paid"]-claim["impairment"],
                "issuerFaceStillUnpaid":sum(c["face"]-c["paid"] for c in book["claims"].values() if c["schemeId"]==scheme)}
    if stage not in ("REGISTERED", "EX_BOOKED", "SETTLED"):
        return row
    rights = book["snapshots"][scheme]
    entries = {r["accountId"]: r for r in rights["entries"]}
    if (rights["schemeId"] != scheme or rights["recordAtMillis"] != terms["recordAtMillis"]
            or len(entries) != len(rights["entries"]) or sum(r["quantity"] for r in entries.values()) != rights["totalQuantity"]
            or sum(r["face"] for r in entries.values()) != rights["totalAmount"]
            or any(r["quantity"] <= 0 or r["face"] != r["quantity"] * terms["perShare"] for r in entries.values())):
        raise ValueError("登记股数、固定权利面额或登记时点不同")
    claims = {k:c for k,c in book["claims"].items() if c["schemeId"] == scheme}
    if stage == "REGISTERED":
        if now != rights["recordAtMillis"] or claims:
            raise ValueError("登记时点错误或登记已提前确认股利应收")
        return {**row, "registeredShares": rights["totalQuantity"], "registeredFace": rights["totalAmount"],
                "registeredAccounts": rights["entries"]}
    if (len(claims) != len(entries)
            or {c["accountId"]:(c["quantity"],c["face"]) for c in claims.values()}
            != {k:(r["quantity"],r["face"]) for k,r in entries.items()}):
        raise ValueError("逐户股利债权与登记权利不符")
    if stage == "EX_BOOKED":
        if now != terms["exAtMillis"] or any(c["paid"] for c in claims.values()):
            raise ValueError("除息时点错误或应收已被提前付现")
        return {**row, "receivablesBooked": rights["totalAmount"], "claims": list(claims.values())}
    cash_event, = proofs
    proof = decode(cash_event["payload"])
    payments = [p for p in book["payments"] if p["schemeId"] == scheme and p["position"] == frame["position"]]
    expected = {k:c["face"] for k,c in claims.items()}
    legs = proof["legs"]
    if (len(payments) != 1 or cash_event["eventId"] != "dividend-cash:" + scheme
            or payments[0]["cashEvidenceId"] != cash_event["eventId"]
            or payments[0]["atMillis"] != now or payments[0]["perClaim"] != expected
            or proof["schemeId"] != scheme or proof["companyAccountId"] != terms["companyAccountId"]
            or payments[0]["amount"] != rights["totalAmount"] or proof["total"] != payments[0]["amount"]
            or len(legs) != len(claims) or {s["claimId"]:s["amount"] for s in legs} != expected
            or any(s["accountId"] != claims[s["claimId"]]["accountId"] or s["accountId"] not in cash for s in legs)
            or any(c["paid"] != c["face"] for c in claims.values())):
        raise ValueError("分红实付凭证与债权、收款账户或结清记录不同")
    changes = Counter({terms["companyAccountId"]: -proof["total"]})
    for leg in legs:
        changes[leg["accountId"]] += leg["amount"]
    if set(cash) != set(previous_cash) or any(cash[k] - previous_cash[k] != changes[k] for k in cash):
        raise ValueError("分红实付与发行人及逐户现金腿不同")
    if followup is not None:
        released = {k:previous_claims[k]["impairment"] for k in claims}
        if (payments[0]["impairmentReleased"] != released or any(c["impairment"] != 0 for c in claims.values())
                or any(previous_claims[k]["face"] != c["face"] or previous_claims[k]["paid"] != 0 for k,c in claims.items())):
            raise ValueError("实付后逐户转回的减值与付款前债权不同")
        row["claimImpairmentReleased"] = sum(released.values())
    return {**row, "cashPaid": proof["total"], "recipients": legs}


def audit(source, target_days=None):
    if target_days is not None and target_days < 0:
        raise ValueError("目标实验日不能为负")
    file_digest = hashlib.sha256()
    prior_digest = hashlib.sha256(b"null").hexdigest()
    state, daily, pairs = {}, {}, Counter()
    prior_time, run_id, opening_total = 0, None, None
    opening, previous_cash, gaps = None, None, []
    decision_owners = {}
    read_ids = {}
    confirmed_trade_ids, filled_by_order = set(), Counter()
    orders, symbols = [], []
    dividends, issuer_symbol = None, None
    dividend_followup = None
    dividend_meetings = set()
    for frame in checked_frames(source, file_digest):
        position = frame["position"]
        events = frame["events"]
        state_events = [e for e in events if e["type"] == "STATE_DELTA_V1"]
        if len(state_events) != 1:
            raise ValueError("需要 delta-v1 十公司日志，每个确认位置应有一份状态差量")
        delta = decode(state_events[0]["payload"])
        run_id = run_id or frame["runId"]
        if run_id not in OPENING_POLICIES or delta["version"] != "delta-v1":
            raise ValueError("此脚本仅复算标准十公司 v2 至 v14 紧凑日志")
        if (frame["runId"] != run_id or delta["runId"] != run_id or delta["position"] != position
                or delta["basePosition"] != position - 1 or delta["baseDigest"] != prior_digest):
            raise ValueError("运行、记录位置或相邻状态摘要不匹配")
        prior_digest = delta["resultDigest"]
        patch = delta["patch"]
        previous_claims = None
        if dividends is not None and any(e["type"] == "DIVIDEND_REPORT_V1" for e in events):
            previous_claims = {key:claim.copy() for key,claim in dividends["claims"].items()}
        if position == 1:
            registries = [decode(e["payload"]) for e in events if e["type"] == "OPENING_REGISTRY_V1"]
            command = checked_opening(frame)
            if registries != [command["registry"]]:
                raise ValueError("首帧登记册与已确认开局命令不符")
            if patch["value"]["runId"] != run_id:
                raise ValueError("开局清单、状态与普通十公司运行身份不符")
            state = {name: patch["value"][name] for name in FIELDS}
            if "issuer-decisions" in OPENING_POLICIES[run_id]:
                dividends = patch["value"]["dividends"]
                issuer_symbol, = [s["symbol"] for s in registries[0]["securities"] if s["issuerOrganizationId"] == "eng-a"]
            if "dividend-claims" in OPENING_POLICIES[run_id]:
                dividend_followup = dividend_followup_context(patch["value"],registries[0])
        else:
            for name in FIELDS:
                if name in patch["changes"]:
                    state[name] = patch_field(state[name], patch["changes"][name])
            if dividends is not None and "dividends" in patch["changes"]:
                dividends = patch_field(dividends, patch["changes"]["dividends"])
        now = state["timeMillis"]
        if now < prior_time:
            raise ValueError("世界时钟倒退")
        day = now // DAY
        if daily and day != prior_time // DAY:
            daily[prior_time // DAY]["closingBook"] = closing_book(orders, symbols)
        orders_changed = position == 1
        if position == 1:
            symbols = [security["symbol"] for security in registries[0]["securities"]]
            orders = patch["value"]["exchange"]["orders"]
        else:
            exchange = patch["changes"].get("exchange", {})
            if exchange.get("kind") == "replace":
                orders = exchange["value"]["orders"]
                orders_changed = True
            elif "orders" in exchange.get("changes", {}):
                orders = patch_field(orders, exchange["changes"]["orders"])
                orders_changed = True
        if day not in daily:
            if daily and day > prior_time // DAY + 1:
                gaps.append({"fromDay": prior_time // DAY + 1, "throughDay": day - 1})
            daily[day] = {"firstPosition": position, "firstTimeMillis": now, "trades": Counter(), "quantity": Counter(),
                          "turnover": Counter(), "orderOutcomes": Counter(), "readsByPerson": Counter(),
                          "decisionDispatches": 0, "dispatchesByPerson": Counter(), "decisionActionsScheduled": 0, "decisionAuditOutcomes": Counter(),
                          "capitalFlows": [], "confirmedEventTypes": Counter()}
        prior_time = now
        row = daily[day]
        row.update(lastPosition=position, lastTimeMillis=now)
        capital_legs = Counter()
        trades, order_reports = [], []
        for event in events:
            kind = event["type"]
            row["confirmedEventTypes"][kind] += 1
            if kind == TRADE:
                t = decode(event["payload"])
                trades.append(t)
                symbol, qty, price = t["symbol"], t["quantity"], t["price"]
                if t["timeMillis"] != now or qty <= 0 or price <= 0:
                    raise ValueError("成交时点、数量或价格不合法")
                if t["tradeId"] in confirmed_trade_ids:
                    raise ValueError("成交编号在确认记录中重复")
                confirmed_trade_ids.add(t["tradeId"])
                filled_by_order[t["buyOrderId"]] += qty
                filled_by_order[t["sellOrderId"]] += qty
                row["trades"][symbol] += 1
                row["quantity"][symbol] += qty
                row["turnover"][symbol] += price * qty
                pairs[t["buyAccountId"], t["sellAccountId"]] += 1
            elif kind == ORDER:
                report = decode(event["payload"])
                if report["timeMillis"] != now or report["position"] != position:
                    raise ValueError("订单回报时点或位置与确认帧不符")
                order_reports.append(report)
                row["orderOutcomes"][f"{report['kind']}/{report['accepted']}/{report['reason']}"] += 1
            elif kind == READ:
                observed = decode(event["payload"])
                if checked_reread(frame, event["payload"], observed, now, read_ids):
                    row["completedRereads"] = row.get("completedRereads", 0) + 1
                row["readsByPerson"][observed["personPartyId"]] += 1
            elif kind == "DECISION_OPENED_V1":
                for decision in decode(event["payload"]):
                    decision_owners[decision["actionId"]] = decision["personPartyId"]
            elif kind == "DECISION_AUDIT_V1":
                audit_row = decode(event["payload"])
                if audit_row["runId"] != run_id or audit_row["position"] != position or audit_row["outcome"] != frame["result"]:
                    raise ValueError("决定审计与确认帧不一致")
                row["decisionAuditOutcomes"][f"{audit_row['kind']}/{audit_row['outcome']}"] += 1
            elif kind == "DECISION_ACTION_SCHEDULED_V1":
                row["decisionActionsScheduled"] += 1
            elif kind in {"CAPITAL_FLOW_V1", "RESOURCE_PRIMARY_CAPITAL_V1"}:
                flow = decode(event["payload"])
                if flow["atMillis"] != now or flow["position"] != position or flow["amount"] <= 0:
                    raise ValueError("实缴资本流的时点、位置或金额不合法")
                capital_legs[flow["fromAccountId"]] -= flow["amount"]
                capital_legs[flow["toAccountId"]] += flow["amount"]
                row["capitalFlows"].append({"eventType": kind, **flow})
            elif kind == "DECISION_DISPATCH_V1":
                dispatch = decode(event["payload"])
                row["dispatchesByPerson"][decision_owners[dispatch["actionId"]]] += 1
                row["decisionDispatches"] += 1
        if trades or order_reports:
            check_trade_orders(trades, order_reports, orders)
        if orders_changed or trades:
            ledger_fills = {o["orderId"]: o["filled"] for o in orders if o["filled"]}
            if ledger_fills != filled_by_order:
                raise ValueError("按买卖原单累计的成交数量与订单账不符")
        cash = {key: account["cash"] for key, account in state["accounts"].items()}
        if dividends is not None:
            entries = row.setdefault("issuerDividendReceipts", [])
            board_receipt = dividend_board_receipt(frame, now, dividend_meetings)
            if board_receipt is not None:
                entries.append(board_receipt)
            if dividend_followup is not None:
                track_dividend_decisions(events,dividend_followup)
            receipt = checked_dividend(frame, now, dividends, previous_cash, cash, issuer_symbol, previous_claims, dividend_followup)
            if position > 1 and "dividends" in patch["changes"] and (receipt is None or not receipt["accepted"]):
                raise ValueError("分红账有变但缺少同帧已接受任务")
            totals = row.setdefault("issuerDividendTotals", dict.fromkeys(("registeredFace","receivablesBooked","cashPaid"),0))
            if dividend_followup is not None:
                totals.setdefault("claimImpairmentAdded",0)
                totals.setdefault("claimImpairmentReleased",0)
            if receipt is not None:
                entries.append(receipt)
                for metric in totals:
                    totals[metric] += receipt.get(metric,0)
        for account, net in capital_legs.items():
            if cash[account] - previous_cash[account] != net:
                raise ValueError("资本实缴事件与同一帧的账户资金增减不符")
        total = sum(cash.values(), Decimal(0))
        if opening_total is None:
            opening_total = total
            registry = registries[0]
            opening = {"position": position, "timeMillis": now, "stateDigest": prior_digest,
                       "cashByAccount": cash, **opening_people(registry, state["accounts"], patch["value"]["securities"]["lots"])}
        if position == row["firstPosition"]:
            row["cashBeforeFirstFrame"] = previous_cash
        previous_cash = cash
        row["cashTotal"] = total
        row["cashByAccount"] = cash
        row["cashChangeFromOpening"] = total - opening_total
    if not daily:
        raise ValueError("没有已确认的开局")
    daily[prior_time // DAY]["closingBook"] = closing_book(orders, symbols)
    return {"runId": run_id, "journalSHA256": file_digest.hexdigest(), "lastPosition": position,
            "lastTimeMillis": prior_time, "lastStateDigest": prior_digest, "openingCash": opening_total, "opening": opening,
            "daysWithoutConfirmedFramesBetweenObservedDays": gaps,
            "targetTimeMillis": None if target_days is None else target_days * DAY,
            "targetTimeReached": None if target_days is None else prior_time >= target_days * DAY,
            "naturalSampleValidity": "NOT_ASSESSED",
            "orderedCounterparties": [{"buy": b, "sell": s, "fills": n} for (b,s),n in sorted(pairs.items())],
            "daysWithConfirmedFrames": daily}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("journal", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--target-days", type=int, help="校准填 90，正式评价填 180；只检查时间覆盖")
    args = parser.parse_args()
    result = audit(args.journal, args.target_days)
    with args.output.open("x", encoding="utf8") as target:
        json.dump(result, target, indent=2, ensure_ascii=False, default=str)
        target.write("\n")
