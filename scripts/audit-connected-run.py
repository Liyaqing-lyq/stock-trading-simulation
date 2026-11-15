"""独立十进制复算 ConnectedWorldRun 的真实导出，不修改世界或补造结果。"""
import collections
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import sys

folder = Path(sys.argv[1])
names = ("opening-accounts", "accounts", "exchange", "operations", "activities")
data = {name: json.loads((folder / (name + ".json")).read_text("utf-8"),
                         parse_float=Decimal) for name in names}
opening, accounts = data["opening-accounts"], data["accounts"]
trades, orders = data["exchange"]["trades"], data["exchange"]["orders"]
activities = data["activities"]
cash_before = sum(row["cash"] for row in opening.values())
cash_after = sum(row["cash"] for row in accounts.values())
assert cash_before == cash_after
assert all(row["cash"] >= 0 for row in accounts.values())
assert len({t["tradeId"] for t in trades}) == len(trades)
by_id = {o["orderId"]: o for o in orders}
filled, cash_delta = collections.Counter(), collections.defaultdict(lambda: Decimal(0))
for t in trades:
    assert t["quantity"] > 0 and t["price"] > 0
    buy, sell = by_id[t["buyOrderId"]], by_id[t["sellOrderId"]]
    assert buy["side"] == "BUY" and sell["side"] == "SELL"
    assert buy["symbol"] == sell["symbol"] == t["symbol"]
    assert buy["accountId"] == t["buyAccountId"]
    assert sell["accountId"] == t["sellAccountId"]
    assert sell["limitPrice"] <= t["price"] <= buy["limitPrice"]
    passive = min((buy, sell), key=lambda order: order["sequence"])
    assert t["price"] == passive["limitPrice"]
    amount = t["price"] * t["quantity"]
    cash_delta[t["buyAccountId"]] -= amount
    cash_delta[t["sellAccountId"]] += amount
    filled[t["buyOrderId"]] += t["quantity"]
    filled[t["sellOrderId"]] += t["quantity"]
for order in orders:
    assert 0 <= order["filled"] == filled[order["orderId"]] <= order["quantity"]
for account in ("ACC-FUND", "INV", "HOUSE-NINA", "HOUSE-OMAR"):
    assert accounts[account]["cash"] - opening[account]["cash"] == cash_delta[account]

# 审计回报证明读完；另要求选择时点晚于一分钟阅读耗时。
for item in activities:
    if item["action"] in ("RESEARCH", "TARGET", "POST"):
        assert any(r["person"] == item["person"] and r["action"] == "READ"
                   and r["sourceDoc"] == item["sourceDoc"] and r["status"] == "已读完"
                   and r["timeMillis"] + 60_000 < item["timeMillis"] for r in activities), item
    previous = {"REVIEW": "TARGET", "CONFIRM": "REVIEW", "CHILD": "CONFIRM"}.get(item["action"])
    if previous:
        assert any(r["action"] == previous and r["status"] == "已执行"
                   and r["details"].get("targetId") == item["details"]["targetId"]
                   and r["timeMillis"] < item["timeMillis"] for r in activities), item

result = {
    "cashBefore": str(cash_before), "cashAfter": str(cash_after),
    "tradeCashLegsAndPassivePrices": "PASS", "orderFills": "PASS",
    "readBeforeResearchTargetOrPost": "PASS", "fourRoleSequence": "PASS",
    "trades": len(trades), "orders": len(orders),
    "quantities": sorted({t["quantity"] for t in trades}),
    "tradesBySymbol": dict(collections.Counter(t["symbol"] for t in trades)),
    "actorsWithDecisions": len({a["person"] for a in activities}),
    "actions": dict(collections.Counter(a["action"] for a in activities)),
    "inputSha256": {name: hashlib.sha256((folder / (name + ".json")).read_bytes()).hexdigest()
                    for name in names},
    "coverage": "真实导出的现金、委托成交、已完成阅读与四岗顺序；不等于经济校准或全部生命周期验收。"
}
print(json.dumps(result, ensure_ascii=False, indent=2))
