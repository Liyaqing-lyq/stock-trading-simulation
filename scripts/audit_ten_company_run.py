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


def decode(raw):
    return json.loads(raw, parse_float=Decimal)


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
    if kind != "object" or not isinstance(before, dict):
        raise ValueError("账户或时点差量类型错误")
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


def audit(source, target_days=None):
    if target_days is not None and target_days < 0:
        raise ValueError("目标实验日不能为负")
    file_digest = hashlib.sha256()
    prior_digest = hashlib.sha256(b"null").hexdigest()
    state, daily, pairs = {}, {}, Counter()
    prior_time, run_id, opening_total = 0, None, None
    opening, previous_cash, gaps = None, None, []
    decision_owners = {}
    for frame in checked_frames(source, file_digest):
        position = frame["position"]
        events = frame["events"]
        state_events = [e for e in events if e["type"] == "STATE_DELTA_V1"]
        if len(state_events) != 1:
            raise ValueError("需要 delta-v1 十公司日志，每个确认位置应有一份状态差量")
        delta = decode(state_events[0]["payload"])
        run_id = run_id or frame["runId"]
        if run_id not in {"web-ten-company-autonomy-v2", "web-ten-company-autonomy-v3"} or delta["version"] != "delta-v1":
            raise ValueError("此脚本仅复算标准十公司 v2、v3 紧凑日志")
        if (frame["runId"] != run_id or delta["runId"] != run_id or delta["position"] != position
                or delta["basePosition"] != position - 1 or delta["baseDigest"] != prior_digest):
            raise ValueError("运行、记录位置或相邻状态摘要不匹配")
        prior_digest = delta["resultDigest"]
        patch = delta["patch"]
        if position == 1:
            manifests = [decode(e["payload"])["manifest"] for e in events if e["type"] == "RUN_MANIFEST_V1"]
            if len(manifests) != 1 or manifests[0]["runId"] != run_id or patch["value"]["runId"] != run_id:
                raise ValueError("开局清单、状态与普通十公司运行身份不符")
            policies = manifests[0]["dataVersions"]
            clock = "document-reading-clock"
            third = run_id == "web-ten-company-autonomy-v3"
            if (policies.get("branch-profile-run") != run_id
                    or (third and policies.get(clock) != "body-pacing-300-v1")
                    or (not third and clock in policies)):
                raise ValueError("开局版本与十公司阅读时钟合同不符")
            state = {name: patch["value"][name] for name in FIELDS}
        else:
            for name in FIELDS:
                if name in patch["changes"]:
                    state[name] = patch_field(state[name], patch["changes"][name])
        now = state["timeMillis"]
        if now < prior_time:
            raise ValueError("世界时钟倒退")
        day = now // DAY
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
        for event in events:
            kind = event["type"]
            row["confirmedEventTypes"][kind] += 1
            if kind == TRADE:
                t = decode(event["payload"])
                symbol, qty, price = t["symbol"], t["quantity"], t["price"]
                if t["timeMillis"] != now or qty <= 0 or price <= 0:
                    raise ValueError("成交时点、数量或价格不合法")
                row["trades"][symbol] += 1
                row["quantity"][symbol] += qty
                row["turnover"][symbol] += price * qty
                pairs[t["buyAccountId"], t["sellAccountId"]] += 1
            elif kind == ORDER:
                report = decode(event["payload"])
                row["orderOutcomes"][f"{report['kind']}/{report['accepted']}/{report['reason']}"] += 1
            elif kind == READ:
                observed = decode(event["payload"])
                if observed["completedAt"] != now:
                    raise ValueError("阅读完成时点与提交不符")
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
        cash = {key: account["cash"] for key, account in state["accounts"].items()}
        for account, net in capital_legs.items():
            if cash[account] - previous_cash[account] != net:
                raise ValueError("资本实缴事件与同一帧的账户资金增减不符")
        total = sum(cash.values(), Decimal(0))
        if opening_total is None:
            opening_total = total
            registry = decode(next(e["payload"] for e in events if e["type"] == "OPENING_REGISTRY_V1"))
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
