"""独立读取已完成的季度导出，用 Decimal 复算；不导入 Java 实现，不改世界日志。"""
import collections
import hashlib
import json
from decimal import Decimal
from pathlib import Path
import sys

DAY = 86_400_000


def read(path):
    return json.loads(path.read_text(encoding="utf-8"), parse_float=Decimal)


def total(values):
    return sum(values, Decimal(0))


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, default=lambda v: format(v, ".2f")), encoding="utf-8")


def audit(folder):
    opening = read(folder / "opening.json")
    initial_cash = {key: value["cash"] for key, value in opening["accounts"].items()}
    world_cash = total(initial_cash.values())
    assert world_cash == Decimal("357500.00")
    allocations = collections.Counter()
    for row in opening["registry"]["allocations"]:
        allocations[row["accountId"], row["symbol"]] += row["quantity"]
    issued = {s["symbol"]: s["issuedShares"] for s in opening["registry"]["securities"]}
    days, last_position = [], opening["position"]
    for day in range(1, 91):
        snapshot = read(folder / f"day-{day:02d}.json")
        assert snapshot["timeMillis"] == day * DAY
        assert snapshot["position"] > last_position
        last_position = snapshot["position"]
        cash = {key: value["cash"] for key, value in snapshot["accounts"].items()}
        assert all(value >= 0 for value in cash.values())
        assert total(cash.values()) == world_cash
        assert Decimal(snapshot["totalCash"]) == world_cash
        trades = snapshot["exchange"]["trades"]
        orders = {o["orderId"]: o for o in snapshot["exchange"]["orders"]}
        assert len(orders) == len(snapshot["exchange"]["orders"])
        assert len({t["tradeId"] for t in trades}) == len(trades)
        shares = allocations.copy()
        trading_cash = {a: initial_cash[a] for a in ("INV", "ACC-FUND")}
        fills = collections.Counter()
        for trade in trades:
            assert 0 < trade["quantity"] and Decimal(trade["price"]) > 0
            assert 0 < trade["timeMillis"] <= snapshot["timeMillis"]
            assert 34_200_000 <= trade["timeMillis"] % DAY <= 54_000_000
            buy, sell = orders[trade["buyOrderId"]], orders[trade["sellOrderId"]]
            assert buy["side"] == "BUY" and sell["side"] == "SELL"
            assert buy["accountId"] == trade["buyAccountId"]
            assert sell["accountId"] == trade["sellAccountId"]
            assert buy["symbol"] == sell["symbol"] == trade["symbol"]
            assert sell["limitPrice"] <= trade["price"] <= buy["limitPrice"]
            passive = min((buy, sell), key=lambda o: o["sequence"])
            assert trade["price"] == passive["limitPrice"]
            amount = trade["price"] * trade["quantity"]
            trading_cash[trade["buyAccountId"]] -= amount
            trading_cash[trade["sellAccountId"]] += amount
            shares[trade["buyAccountId"], trade["symbol"]] += trade["quantity"]
            shares[trade["sellAccountId"], trade["symbol"]] -= trade["quantity"]
            fills[buy["orderId"]] += trade["quantity"]
            fills[sell["orderId"]] += trade["quantity"]
        assert all(cash[a] == value for a, value in trading_cash.items())
        actual_shares = collections.Counter()
        supply = collections.Counter()
        for lot in snapshot["securities"]["lots"]:
            assert lot["remaining"] >= 0
            actual_shares[lot["accountId"], lot["symbol"]] += lot["remaining"]
            supply[lot["symbol"]] += lot["remaining"]
            if lot["acquiredAtMillis"] > 0:
                assert lot["eligibleFromDay"] == lot["acquiredAtMillis"] // DAY + 1
        assert actual_shares == shares and dict(supply) == issued
        assert snapshot["securities"]["reservations"] == []
        for order in orders.values():
            assert order["filled"] == fills[order["orderId"]]
            assert 0 <= order["filled"] <= order["quantity"]
            assert order["status"] in ("FILLED", "CANCELLED", "REJECTED", "EXPIRED")
        for report in snapshot["companyReports"]:
            profit = total(report["income"].values()) - total(report["expense"].values())
            net_assets = total(report["assets"].values()) - total(report.get("contra", {}).values())
            liabilities = total(report["liabilities"].values())
            equity = total(report["equity"].values())
            if report["companyPartyId"] == "north":
                assert profit == report["currentEarnings"]
                equity += profit
            else:
                assert profit == report["equity"]["本报表累计损益"]
            assert net_assets == liabilities + equity
        today = [t for t in trades if (day - 1) * DAY < t["timeMillis"] <= day * DAY]
        days.append(dict(day=day, trades=len(today), volume=sum(t["quantity"] for t in today),
                         turnover=total(t["price"] * t["quantity"] for t in today),
                         totalCash=world_cash, position=snapshot["position"]))
    final = read(folder / "final.json")
    assert final == snapshot == read(folder / "recovered.json")
    assert read(folder / "recovery.json")["equal"] is True
    operations = final["companyOperations"]
    for company in ("aged-company", "digital-company", "resources", "mfg-tech", "mfg-struct", "mfg-versa"):
        for period in range(3):
            close = operations["reports"][f"ops-done:{company}:CLOSE_PERIOD:{period}"]
            assert close["accepted"] and close["timeMillis"] == (period + 1) * 30 * DAY
    for row in final["engineering"]["periodic"]["companies"]:
        assert [r["period"] for r in row["snapshots"]] == [1, 2, 3]
    digital = operations["digital"]["ledger"]
    for period in (0, 1):
        receivable = digital["receivables"][f"digital-{period}-ar"]
        assert receivable["paid"] == receivable["amount"] and receivable["status"] == "PAID"
    third = digital["receivables"]["digital-2-ar"]
    assert third["paid"] == 0 and third["dueAt"] > final["timeMillis"]
    stages = final["researchRun"]["state"]["projects"]["p1"]["stages"]
    assert [s["status"] for s in stages] == ["COMPLETED", "IN_PROGRESS", "PLANNED"]
    assert stages[1]["completesAt"] > final["timeMillis"]
    aged = operations["aged"]["ledger"]
    assert [b["matureAtPeriod"] for b in aged["batches"]] == [3, 4]
    assert aged["sales"] == []
    rejected = [r for r in operations["reports"].values() if not r["accepted"]]
    assert collections.Counter((r["companyPartyId"], r["action"], r["reason"]) for r in rejected) == {
        ("digital-company", "COLLECT", "RECEIVABLE_NOT_DUE"): 1,
        ("aged-company", "SELL_THROUGH", "INVALID_QUANTITY"): 1,
    }
    checks = ["90 个每日时点连续，三次 30 日结账及治理快照完整",
              "逐日现金合计 357,500.00；无负现金、无新增外部注资",
              "逐日按初始分配与真实成交复算全部持股；10 个标的总股数守恒",
              "买卖双方现金、成交数量和被动方价格独立复算一致；每日收盘无活动订单与证券冻结",
              "十家公司逐日报表平衡；收入减费用与累计损益一致",
              "季度末关闭并重新打开完整日志，恢复状态与每日最终导出完全一致",
              "首两期数字应收实际到账；第三期未到期；药研与陈酿保留真实在途"]
    stage_text = {"mfg-tech": "三期采购、生产、交付与回款", "mfg-struct": "三期采购、生产、交付与回款",
                  "mfg-versa": "三期采购、生产、交付与回款", "resources": "有限开采、煤外售与发电，三期回款",
                  "north": "存款与准备金可核对；本季未新增贷款", "eng-a": "工程完成并开票，应收尚未支付",
                  "digital-company": "服务准入、三期交付、两期收款，第三期尚未到期",
                  "aged-company": "首批季末成熟；终端需求到来时无寄售库存，拒绝零量售出，本季零销售",
                  "hydro": "真实来水、发电、应收与有限电网付款", "pharma": "研究完成、开发在途、注册未开始"}
    companies = []
    for report in final["companyReports"]:
        company = report["companyPartyId"]
        income, expense = total(report["income"].values()), total(report["expense"].values())
        companies.append(dict(name=report["name"], cash=Decimal(report.get("financial", {}).get("cash", report["assets"].get("RESERVE"))),
                              income=income, expense=expense, profit=income-expense, stage=stage_text[company]))
    report = dict(schema="world-cycle-run-v1", runId="web-industry-prototype-v1",
                  endPosition=final["position"], endTimeMillis=final["timeMillis"],
                  summary=f"90 个实验日 · 3 次结账 · {sum(d['trades'] for d in days)} 笔成交 · 十家公司",
                  note="这是冻结在第 90 日的历史报告。经营与交易采用可审计规则，非全自主模型绩效。另一个试跑中的 3 次真实模型调用均为观望，不计入本季度。连续实验日不对应现实交易日历；参数未作现实收益校准。",
                  checks=checks, companies=companies, days=days)
    save(folder / "experiment-report.json", report)
    save(folder / "independent-audit.json", dict(passed=True, checks=checks, operationalRejections=rejected,
         trades=sum(d["trades"] for d in days), turnover=total(d["turnover"] for d in days), companies=companies,
         dayFilesSha256={p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(folder.glob("day-*.json"))},
         journalSha256=hashlib.file_digest((folder / "world-industry-prototype-v1.journal").open("rb"), "sha256").hexdigest()))
    print(json.dumps(dict(passed=True, days=90, position=final["position"], trades=sum(d["trades"] for d in days),
                         turnover=str(total(d["turnover"] for d in days))), ensure_ascii=False))


if __name__ == "__main__":
    audit(Path(sys.argv[1]))
