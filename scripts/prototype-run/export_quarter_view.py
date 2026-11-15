"""从已核验90日档案导出页面需要的完整报表和成交日序列；不修改世界。"""
import hashlib
import json
from decimal import Decimal
from pathlib import Path
import sys


def read(path):
    return json.loads(path.read_text(encoding="utf-8"), parse_float=Decimal)


def cash(report):
    return report["assets"]["RESERVE"] if report["companyPartyId"] == "north" else report["financial"]["cash"]


def statement(report, previous=None):
    result = {k: report.get(k, {}) for k in ("assets", "contra", "liabilities", "equity", "income", "expense")}
    result["equity"] = dict(result["equity"])
    if report["companyPartyId"] == "north":
        result["equity"]["本报表累计损益"] = report["currentEarnings"]
    result["totalAssets"] = sum(result["assets"].values()) - sum(result["contra"].values())
    result["totalLiabilities"] = sum(result["liabilities"].values())
    result["totalEquity"] = sum(result["equity"].values())
    result["profit"] = sum(result["income"].values()) - sum(result["expense"].values())
    assert result["totalAssets"] == result["totalLiabilities"] + result["totalEquity"]
    for key in ("income", "expense"):
        before = previous[key] if previous else {}
        result[key + "Period"] = {k: result[key].get(k, 0)-before.get(k, 0) for k in sorted(result[key].keys() | before.keys())}
    result["profitPeriod"] = sum(result["incomePeriod"].values())-sum(result["expensePeriod"].values())
    return result


def export(source, target):
    assert target.resolve() != (source/"experiment-report.json").resolve(), "不能覆盖已核验原始报告"
    audit = read(source/"independent-audit.json")
    assert audit["passed"]
    result = read(source/"experiment-report.json")
    opening = read(source/"opening.json")
    details = {s["issuerOrganizationId"]: dict(symbol=s["symbol"], referencePrice=s["initialReferencePrice"], days=[], periods=[])
               for s in opening["registry"]["securities"]}
    open_reports = {r["companyPartyId"]: r for r in opening["companyReports"]}
    previous = {}
    for day in range(1, 91):
        path=source/f"day-{day:02d}.json"
        assert hashlib.sha256(path.read_bytes()).hexdigest() == audit["dayFilesSha256"][path.name]
        state = read(path)
        for report in state["companyReports"]:
            company=report["companyPartyId"]; row=details[company]
            all_trades=[t for t in state["exchange"]["trades"] if t["symbol"]==row["symbol"]]
            today=[t for t in all_trades if (day-1)*86_400_000 < t["timeMillis"] <= day*86_400_000]
            row["days"].append(dict(day=day, cash=cash(report), profit=sum(report["income"].values())-sum(report["expense"].values()),
                trades=len(today), volume=sum(t["quantity"] for t in today),
                close=today[-1]["price"] if today else None,
                lastPrice=all_trades[-1]["price"] if all_trades else None,
                turnover=sum((t["price"]*t["quantity"] for t in today), Decimal(0))))
            if day % 30 == 0:
                row["periods"].append(dict(period=day//30,day=day,cash=cash(report),statement=statement(report,previous.get(company))))
                previous[company]=report
    for company,row in details.items():
        row["openingCash"]=cash(open_reports[company])
        row["cashChange"]=row["days"][-1]["cash"]-row["openingCash"]
    result["details"] = details
    result["detailSource"] = dict(schema="world-quarter-details-v1",endPosition=result["endPosition"],
        journalSha256=audit["journalSha256"],note="完整报表来自每日同帧导出；无成交日close为空，lastPrice仅延续此前真实成交价。")
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(json.dumps(result,ensure_ascii=False,indent=2,default=lambda v:format(v,".2f")),encoding="utf-8")
    print(f"已核验并导出：{len(details)}家公司、900个公司日记录、30份完整期末报表。")


if __name__ == "__main__":
    export(Path(sys.argv[1]), Path(sys.argv[2]))
