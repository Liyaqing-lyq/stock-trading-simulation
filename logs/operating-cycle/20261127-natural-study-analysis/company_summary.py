"""按已确认快照与同截止审计投影生成十公司摘要；不调用 Java 或服务。"""
import argparse
from collections import Counter
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import sys

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "scripts"))
from audit_ten_company_run import checked_frames, decode

FIELDS = ("timeMillis", "accounts", "roles", "bank", "research", "companyOperations", "engineering", "companyGovernance")
ABSENT = object()


def apply(before, patch):
    kind = patch["kind"]
    if kind == "same":
        return before
    if kind == "replace":
        return patch["value"]
    if kind == "object":
        for key in patch["removed"]:
            del before[key]
        for key, op in patch["changes"].items():
            before[key] = apply(before.get(key), op)
        return before
    if kind == "array":
        length = patch["length"]
        del before[length:]
        before.extend([None] * (length - len(before)))
        for index, op in patch["changes"].items():
            index = int(index)
            before[index] = apply(before[index], op)
        return before
    raise ValueError(f"未知差量 {kind}")


def at(tree, keys):
    for key in keys:
        if not isinstance(tree, dict) or key not in tree:
            return ABSENT
        tree = tree[key]
    return tree


def path_part(label):
    return str(label).replace("~", "~0").replace("/", "~1")


def fact(label, value):
    return {"source": label, "value": None if value is ABSENT else value,
            "status": "MISSING" if value is ABSENT or value is None else "REPORTED"}


def facts(tree, prefix, names):
    return {key: fact(prefix+"/"+path_part(key), at(tree,[key])) for key in names}


def journal_at(path, position, runid, now):
    state, digest = {}, hashlib.sha256()
    previous = hashlib.sha256(b"null").hexdigest()
    found = False
    last = 0
    closing = None
    bank_completed = []
    bank_opening = None
    listed = {}
    for frame in checked_frames(path, digest):
        last = frame["position"]
        if frame["runId"] != runid:
            raise ValueError("result 与 journal 的 runId 不同")
        if last > position:
            continue
        events = [e for e in frame["events"] if e["type"] == "STATE_DELTA_V1"]
        if len(events) != 1:
            raise ValueError("此入口需要从开局起的标准 delta-v1 原档")
        delta = decode(events[0]["payload"])
        if delta["version"] != "delta-v1" or delta["runId"] != runid or delta["basePosition"] != last-1 or delta["position"] != last or delta["baseDigest"] != previous:
            raise ValueError("状态差量链接错误")
        previous = delta["resultDigest"]
        draw = None
        for event in frame["events"]:
            if event["type"] == "OPENING_REGISTRY_V1" and last == 1:
                for security in decode(event["payload"])["securities"]:
                    listed[security["issuerOrganizationId"]] = security["symbol"]
            elif event["type"] == "BANK_ACTION_V1":
                entry = decode(event["payload"])
                if entry["position"] != last:
                    raise ValueError("银行动作回报位置与确认帧不符")
                if entry["accepted"] and entry["kind"] == "LOAN_DRAW":
                    req_event = next(e for e in frame["events"] if e["type"] == "TASK_COMPLETED_V1")
                    req = decode(decode(req_event["payload"])["requestJson"])
                    if req["loanId"] is None or req["bankKey"] != entry["bankKey"] or req["customerKey"] != entry["customerKey"]:
                        raise ValueError("已放款回报与完成的请求不符")
                    draw = {**entry,"loanId":req["loanId"]}
                    old_book = decode(state["bank"]["stateJson"])

        p = delta["patch"]
        if last == 1:
            state = {k:v for k,v in p["value"].items() if k in FIELDS}
            bank_opening = state["bank"]["stateJson"] if state.get("bank") else None
        else:
            for k in FIELDS:
                if k in p.get("removed", []):
                    state.pop(k,None)
                if k in p["changes"]:
                    state[k] = apply(state.get(k), p["changes"][k])
        if draw:
            lender, borrower = draw["bankKey"],draw["customerKey"]
            key = f"L|{len(lender)}:{lender}|{len(borrower)}:{borrower}"
            def principal(book):
                return sum((t["onbook"] for t in book["loans"].get(key,{}).get("tranches",[])),Decimal(0))
            increase = principal(decode(state["bank"]["stateJson"])) - principal(old_book)
            if increase <= 0:
                raise ValueError("放款回报缺对应贷款本金增加")
            bank_completed.append({**draw,"bookPrincipalIncrease":increase,"loanLedgerKey":key})
        if last == position:
            found = True
            closing = previous
    if not found or state.get("timeMillis") != now:
        raise ValueError("日志没有 result 声明的同一截止")
    state["_bankCompleted"] = bank_completed
    state["_openingBank"] = decode(bank_opening) if bank_opening else {}
    state["_listed"] = listed
    return state, {"sha256":digest.hexdigest(),"lastPositionInFile":last, "analyzedPosition":position,"analyzedStateDigest":closing}


def snapshot_agrees(result, state):
    accounts = {a["accountId"]:a for a in result["admin"]["accounts"]}
    raw = state["accounts"]
    if set(accounts) != set(raw) or any(Decimal(a["cash"]) != raw[k]["cash"] or a["ownerId"] != raw[k]["ownerId"] for k,a in accounts.items()):
        raise ValueError("result 的逐户现金/财产主体与日志同截止实账不符")
    for row in [result["operations"], *result["companyGovernance"].values()]:
        if str(row["position"]) != str(result["admin"]["position"]) or str(row["timeMillis"]) != str(result["admin"]["timeMillis"]):
            raise ValueError("result 的多个页面不是同一截止")
        if row.get("runId", result["admin"]["runId"]) != result["admin"]["runId"]:
            raise ValueError("治理页面的运行标识不一致")


def for_bank(state, bank_id):
    if "bank" not in state or state["bank"] is None:
        return fact("journal:/bank", ABSENT)
    book = decode(state["bank"]["stateJson"])
    src = "journal:/bank/stateJson(JSON)"
    gl = at(book, ["glByBank",bank_id])
    if gl is ABSENT:
        return fact(src+"/glByBank/"+bank_id, ABSENT)
    # 银行账的复合键对 bank 字段带字符长度；仅选择精确本行前缀。
    bank_part = str(len(bank_id))+":"+bank_id+"|"
    loan_prefix, deposit_prefix = "L|"+bank_part, "D|"+bank_part
    loans, deposits = at(book,["loans"]), at(book,["depositAccounts"])
    loans = {k:v for k,v in loans.items() if k.startswith(loan_prefix)} if isinstance(loans,dict) else ABSENT
    deposits = {k:v for k,v in deposits.items() if k.startswith(deposit_prefix)} if isinstance(deposits,dict) else ABSENT
    config = decode(state["bank"]["configJson"])["profile"]
    dep_gl = {party["depositGl"] for party in config["parties"].values() if party["depositGl"] is not None}
    dep = {key:-value for key,value in gl.items() if key in dep_gl}
    older = state["_openingBank"].get("loans",{})
    opening_book = {k:v for k,v in older.items() if k.startswith(loan_prefix)}
    return {"reserve":fact(src+"/glByBank/"+bank_id+"/RESERVE",at(gl,["RESERVE"])),
            "depositPrincipalByLedgerAccount":fact(src+"/glByBank/"+bank_id+"/DEPOSIT-* (liability credit negated)",dep),
            "openingLoanPositions":fact("journal@1:/bank/stateJson(JSON)/loans/"+loan_prefix+"*",opening_book),
            "confirmedNewDraws":fact("journal:events/BANK_ACTION_V1 [accepted=true, kind=LOAN_DRAW, bankKey="+bank_id+"]",
                [q for q in state["_bankCompleted"] if q["bankKey"]==bank_id and q["accepted"] and q["kind"]=="LOAN_DRAW"]),
            "depositContracts":fact(src+"/depositAccounts/"+deposit_prefix+"*",deposits),
            "loanPositions":fact(src+"/loans/"+loan_prefix+"*",loans),
            "onBookLoanPrincipal":fact(src+"/loans/"+loan_prefix+"*/tranches/*/onbook (sum)",
                sum((t["onbook"] for v in loans.values() for t in v["tranches"]),Decimal(0)) if loans is not ABSENT else ABSENT),
            "accountBalancesAsBooked":fact(src+"/glByBank/"+bank_id,gl)}


def company(result, state, issuer, op, now):
    gov = result["companyGovernance"].get(issuer,{})
    gp = "result:/companyGovernance/"+path_part(issuer)
    data = {"symbol":state["_listed"][issuer], "name":gov.get("companyName"), "template":gov.get("template")}
    appointments = at(state,["roles","access","appointments"])
    local = [a for a in appointments if a["organizationPartyId"]==issuer] if isinstance(appointments,list) else ABSENT
    if local is not ABSENT:
        local = [{**a,"activeAtCut":a["startsAt"]<=now<a["endsAt"] and (a["revokedAt"] is None or now<a["revokedAt"])} for a in local]
    data["appointmentsAndTerms"]=fact("journal:/roles/access/appointments [organizationPartyId="+issuer+"]",local)
    section = "engineering" if issuer in state.get("engineering",{}).get("boards",{}) else "companyGovernance"
    bp = "journal:/"+section+"/boards/"+path_part(issuer)+"/board"
    board=at(state,[section,"boards",issuer,"board"])
    data["governance"] = facts(board,bp,("charter","meetings","budgets","commitments"))
    meetings = at(board,["meetings"])
    if isinstance(meetings,dict):
        data["ballotsByMeeting"] = fact(bp+"/meetings/*/ballots",
                  {k:dict(Counter(q["vote"] for q in m["ballots"].values())) for k,m in meetings.items()})
    data["engineeringCostAndRevenue"] = fact(gp+"/report",at(gov,["report"]))
    data["ownOperationsReport"] = None
    if op:
        index, business = op
        root="result:/operations/companies/"+str(index)
        fin=business.get("financial",{})
        data["ownOperationsReport"] = {**facts(fin,root+"/financial",("income","assets","liabilities")),
             "reports":fact(root+"/reports",at(business,["reports"]))}
        plan=business.get("plan",{})
        selected = {
            "HYDRO":("plant","physicalAsOf","contract"),
            "DIGITAL":("contracts","receivables","bills","closedPeriods","stages"),
            "AGED":("orders","sales","batches","rawLots","channelLedgers","shipmentLots","consignmentUnits","soldUnits","receivable","inventoryCost","contractLiability","closes"),
            "RESOURCE":("jobs","mineCoal","plantFuel","receivables","periodUse","residualAssets","expansions")
        }
        data["bookedBusinessRecords"] = {**facts(business,root,("contracts","receivables","purchases","batches","materials","products","jobs")),
              "templateRecords":facts(plan,root+"/plan",selected.get(gov.get("template"),()))}
    else:
        data["bookedBusinessRecords"] = {}
    if gov.get("template")=="ENGINEERING":
        data["bookedBusinessRecords"].update(facts(gov,gp,("contracts","workOrders","payments","waivers")))
    t = gov.get("template")
    base = "journal:/companyOperations"
    missing = fact("本模板的两份同截止输入未提供此指标",ABSENT)
    metrics = {key:missing for key in ("operatingRevenue","customerCollections","customerPrepayments",
                                      "receivables","inventory","signedContracts","fulfilment")}
    if op:
        _,business = op
        income_key = {"HYDRO":"售电收入（累计）","RESOURCE":"外部营业收入（累计）","DIGITAL":"服务收入（累计）",
                      "MANUFACTURING":"营业收入（累计）","AGED":"终端售出收入（累计）"}.get(t)
        asset_key = {"HYDRO":"应收电费","RESOURCE":"应收","DIGITAL":"应收服务款","MANUFACTURING":"应收","AGED":"应收渠道款"}.get(t)
        for metric,dkey,key in [("operatingRevenue","income",income_key),("receivables","assets",asset_key)]:
            if key:
                metrics[metric] = fact(root+"/financial/"+dkey+"/"+key,at(fin,[dkey,key]))
        inv = {"RESOURCE":["矿端库存煤","厂内燃料","在制品"], "AGED":["存货成本"], "MANUFACTURING":["原材料","在制","成品"]}.get(t)
        if inv:
            metrics["inventory"] = facts(fin.get("assets",{}),root+"/financial/assets",inv)
        def sum_field(rows,key):
            if not isinstance(rows,(list,dict)):
                return ABSENT
            rows = list(rows.values()) if isinstance(rows,dict) else rows
            if any(not isinstance(r,dict) or r.get(key) is None for r in rows):
                return ABSENT
            return sum((Decimal(r[key]) for r in rows),Decimal(0))
        if t=="HYDRO":
            for key,name in (("collectedCash","customerCollections"),("deliveredKilowattHours","fulfilment")):
                metrics[name]=fact(root+"/plant/"+key,at(business,["plant",key]))
            metrics["signedContracts"]=fact(root+"/plan/contract (开局购电规则，签署时点未给出)",ABSENT)
        if t in {"DIGITAL","RESOURCE"}:
            sub="digital" if t=="DIGITAL" else "resource"
            ls=at(state,["companyOperations",sub,"ledger"])
            a=base+"/"+sub+"/ledger"
            metrics["customerCollections"]=fact(a+"/receivables/*/paid (sum)",sum_field(at(ls,["receivables"]),"paid"))
            if t=="DIGITAL":
                for key,name in (("prepaid","customerPrepayments"),("delivered","fulfilment")):
                    metrics[name]=fact(a+"/contracts/*/"+key+" (sum)",sum_field(at(ls,["contracts"]),key))
                metrics["signedContracts"]=fact(a+"/contracts",at(ls,["contracts"]))
            else:
                metrics["fulfilment"]=fact(a+"/receivables [客户、实交品类和数量]",at(ls,["receivables"]))
        if t=="MANUFACTURING":
            all_reports=at(state,["companyOperations","reports"])
            if isinstance(all_reports,dict):
                paid=[q for q in all_reports.values() if q["companyPartyId"]==issuer and q["accepted"] and q["action"]=="COLLECT"]
                metrics["customerCollections"]=fact(base+"/reports [company="+issuer+", accepted COLLECT]/amount (sum)",sum_field(paid,"amount"))
            for key,name in (("contracts","signedContracts"),("contracts","fulfilment")):
                value=at(business,[key]);label=root+"/"+key
                if name=="fulfilment":
                    value=sum_field(value,"delivered");label+="/*/delivered (sum)"
                metrics[name]=fact(label,value)
        if t=="AGED":
            for key,name in (("prepaidReceived","customerPrepayments"),("collected","customerCollections")):
                metrics[name]=fact(root+"/plan/channelLedgers/*/"+key+" (sum)",sum_field(plan.get("channelLedgers"),key))
            metrics["signedContracts"]=fact(root+"/plan/orders (已入账订货)",at(plan,["orders"]))
            metrics["fulfilment"]=fact(root+"/plan/sales (实际终端销售)",at(plan,["sales"]))
    if t=="ENGINEERING":
        metrics["operatingRevenue"]=fact(gp+"/report/revenue",at(gov,["report","revenue"]))
        metrics["receivables"]=facts(gov.get("report",{}),gp+"/report",("arCurrent","arRetention"))
        ep="journal:/engineering/companies/"+issuer
        ec=at(state,["engineering","companies",issuer])
        metrics["signedContracts"]=fact(ep+"/contracts",at(ec,["contracts"]))
        metrics["fulfilment"]=fact(ep+"/workOrders",at(ec,["workOrders"]))
        payments=at(state,["engineering","customer","payments"])
        if isinstance(payments,list):
            metrics["customerPoolPayments"]=fact("journal:/engineering/customer/payments [company="+issuer+"]", [q for q in payments if q["companyPartyId"]==issuer])
    data["operatingMetrics"]=metrics
    if issuer == "pharma":
        data["bookedBusinessRecords"]["researchProjects"]=fact("journal:/research/state/projects",at(state,["research","state","projects"]))
    if issuer == "north":
        data["bank"]=for_bank(state,issuer)
    return data


def summarize(result_path, journal_path):
    raw = result_path.read_bytes()
    result = decode(raw)
    admin = result["admin"]
    pos, now = int(admin["position"]), int(admin["timeMillis"])
    state, journal = journal_at(journal_path,pos,admin["runId"],now)
    snapshot_agrees(result,state)
    ops={row["companyId"]:(i,row) for i,row in enumerate(result["operations"]["companies"])}
    rows={k:company(result,state,k,ops.get(k),now) for k in state["_listed"]}
    return {"runId":admin["runId"],"cutoffTimeMillis":now,"result":{"path":str(result_path),"sha256":hashlib.sha256(raw).hexdigest(),"stop":result.get("probeStop")},"journal":{"path":str(journal_path),**journal},"companies":rows}


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("result",type=Path);parser.add_argument("journal",type=Path);parser.add_argument("output",type=Path)
    args=parser.parse_args()
    data=summarize(args.result,args.journal)
    with args.output.open('x',encoding='utf8') as out:
        json.dump(data,out,ensure_ascii=False,indent=2,default=str);out.write("\n")
