"""只读核对成交当帧的实际资金桶、持股及对手；输入标准十公司完整日志。"""
from collections import Counter
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import argparse

from audit_ten_company_run import (
    DAY, ORDER, TRADE, checked_opening, check_trade_orders, checked_frames, decode, patch_field
)


def balance(state, token):
    parts = token.split("|")
    if parts[0] == "CASH" and len(parts) == 2:
        return state["accounts"][parts[1]]["cash"]
    if parts[0] == "DEPOSIT" and len(parts) == 3:
        bank, holder = parts[1:]
        profile = decode(state["bank"]["configJson"])["profile"]
        key = (profile["parties"][holder]["depositGl"] if holder in profile["parties"]
               else profile["custodians"][holder]["liabilityId"])
        if not key:
            raise ValueError("存款账户缺少登记科目 " + token)
        gl = decode(state["bank"]["stateJson"])["glByBank"][bank]
        return -gl.get(key, Decimal(0))
    if parts[0] == "CUSTODY" and len(parts) == 3:
        profile = decode(state["bank"]["configJson"])["profile"]
        if parts[1] not in profile["custodians"] or parts[2] not in profile["parties"]:
            raise ValueError("未登记托管人或客户 " + token)
        details = decode(state["bank"]["custodyJson"])["details"]
        return details.get("|".join(parts[1:]), Decimal(0))
    raise ValueError("未登记资金桶 " + token)


def holdings(state):
    result = Counter()
    for lot in state["securities"]["lots"]:
        result[lot["accountId"], lot["symbol"]] += lot["remaining"]
    return result


def audit(path):
    content_hash = hashlib.sha256()
    out = {"tradeCount": 0, "framesWithTrades": [], "days": {}}
    state = {}
    prior_digest = hashlib.sha256(b"null").hexdigest()
    for frame in checked_frames(path, content_hash):
        events = frame["events"]
        trades = [decode(e["payload"]) for e in events if e["type"] == TRADE]
        delta, = [decode(e["payload"]) for e in events if e["type"] == "STATE_DELTA_V1"]
        position = frame["position"]
        if delta["baseDigest"] != prior_digest or delta["position"] != position or delta["runId"] != frame["runId"]:
            raise ValueError("状态摘要前缀或确认位置不符")
        prior_digest = delta["resultDigest"]
        if trades:
            # 订单差量会原地更新余额。保留成交前的账目，旧的成交链和报告无需再次复制。
            previous = {"accounts": {k: {"cash": v["cash"]} for k, v in state["accounts"].items()},
                        "bank": state["bank"].copy()}
            before_units = holdings(state)
        patch = delta["patch"]
        keys = ("accounts", "exchange", "securities", "bank")
        if position == 1:
            checked_opening(frame)
            state = {k: patch["value"][k] for k in keys}
            out["runId"] = frame["runId"]
            now = patch["value"]["timeMillis"]
        else:
            if frame["runId"] != out["runId"]:
                raise ValueError("运行身份中途变更")
            if "timeMillis" in patch["changes"]:
                updated = patch_field(now, patch["changes"]["timeMillis"])
                if updated < now:
                    raise ValueError("世界时间倒退")
                now = updated
            for k in keys:
                if k in patch["changes"]:
                    state[k] = patch_field(state[k], patch["changes"][k])
        out["lastPosition"] = position
        out["lastTimeMillis"] = now
        if not trades:
            continue
        reports = [decode(e["payload"]) for e in events if e["type"] == ORDER]
        if any(t["timeMillis"] != now or t["price"] <= 0 or t["quantity"] <= 0 for t in trades):
            raise ValueError("成交时点或量价与确认帧不符")
        if any(r["position"] != position or r["timeMillis"] != now for r in reports):
            raise ValueError("委托回报与确认帧不符")
        orders = state["exchange"]["orders"]
        check_trade_orders(trades, reports, orders)
        by_order = {o["orderId"]: o for o in orders}
        amounts, units = Counter(), Counter()
        rows = []
        for t in trades:
            price, qty = t["price"], t["quantity"]
            gross = price * qty
            row = {**t, "amount": gross}
            for side, sign in (("buy", 1), ("sell", -1)):
                order = by_order[t[side + "OrderId"]]
                account = order["accountId"]
                token = order.get("fundingToken") or "CASH|" + account
                row[side + "Funding"] = token
                amounts[token] -= sign * gross
                units[account, t["symbol"]] += sign * qty
            rows.append(row)
            day = out["days"].setdefault(t["timeMillis"] // DAY, {"trades": 0, "volume": 0, "turnover": Decimal(0), "pairs": Counter()})
            day["trades"] += 1
            day["volume"] += qty
            day["turnover"] += gross
            day["pairs"][json.dumps([t["buyAccountId"], t["sellAccountId"]])] += 1
        out["tradeCount"] += len(trades)
        after_units = holdings(state)
        cash = []
        for bucket, expected in amounts.items():
            before, after = balance(previous, bucket), balance(state, bucket)
            if after - before != expected:
                raise ValueError(f"第{position}帧资金桶 {bucket} 变动 {after-before} 与成交合计 {expected} 不同")
            cash.append({"bucket": bucket, "before": before, "after": after, "change": expected})
        shares = []
        for (account, symbol), expected in units.items():
            before, after = before_units[account, symbol], after_units[account, symbol]
            if after - before != expected:
                raise ValueError(f"第{position}帧 {account}/{symbol} 持股变动 {after-before} 与成交合计 {expected} 不同")
            shares.append({"account": account, "symbol": symbol, "before": before, "after": after, "change": expected})
        out["framesWithTrades"].append({"position": position, "trades": rows, "money": cash, "shares": shares})
    if not state:
        raise ValueError("未找到确认的开局")
    out["journalSHA256"] = content_hash.hexdigest()
    return out


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("journal", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("输出文件已存在")
    report = audit(args.journal)
    with args.output.open("x", encoding="utf8", newline="\n") as file:
        json.dump(report, file, indent=2, ensure_ascii=False, default=str)
        file.write("\n")
