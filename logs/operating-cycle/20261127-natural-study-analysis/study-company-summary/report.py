"""十公司日/季观察表。用冻结构建的正式报表，输入为从开局连续封存的 journal。"""
import argparse
from collections import Counter
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from company_summary import apply
from audit_ten_company_run import checked_frames, checked_opening, checked_dividend, dividend_board_receipt, OPENING_POLICIES, decode, DAY
from audit_ten_company_run import dividend_followup_context, track_dividend_decisions


# Each close obtains the same statements as the normal read-only reports. The official
# opener may append initialization records to a new file, so it only receives an existing
# scratch prefix and both its byte size and its restored position are verified.
JAVA = r"""
import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.composed.*;
public class Statements {
  public static void main(String[] args) throws Exception {
    var json = new ObjectMapper();
    var dst = Path.of("prefix.journal");
    try (var input = new RandomAccessFile(args[0], "r")) {
      for (String line : Files.readAllLines(Path.of(args[1]))) {
        String[] fields = line.split(" ");
        long pos = Long.parseLong(fields[0]), end = Long.parseLong(fields[1]);
        try (var output = new FileOutputStream(dst.toFile(), true)) {
          byte[] buffer = new byte[131072];
          while (input.getFilePointer() < end) {
            int n = input.read(buffer, 0, (int)Math.min(buffer.length, end-input.getFilePointer()));
            if (n <= 0) throw new EOFException();
            output.write(buffer, 0, n);
          }
        }
        var rows = new TreeMap<String,Object>();
        try (var w = ComposedIndustryScenario.openDigitalWindDownTenCompanyStudy(dst)) {
          if (w.snapshot().position()!=pos) throw new IOException("restored position mismatch");
          for (var sec : w.openingRegistry().orElseThrow().securities()) {
            String id=sec.issuerOrganizationId();
            var row=new LinkedHashMap<String,Object>();
            if (id.equals("north")) {
              var s=BankCompanyReport.of(w.bankOf().orElseThrow(), w.snapshot().accounts(),id);
              row.put("assets",s.totalAssets()); row.put("liabilities",s.totalLiabilities());
              row.put("equity",s.totalEquity()); row.put("report",s); row.put("balanced",s.balanced());
            } else {
              var s=ComposedCompanyReport.of(w,id);
              row.put("assets",s.totalAssets()); row.put("liabilities",s.totalLiabilities());
              row.put("equity",s.totalEquity()); row.put("report",s); row.put("balanced",s.balanced());
            }
            rows.put(id,row);
          }
        }
        if (Files.size(dst)!=end) throw new IOException("prefix changed during reopen");
        System.out.write(json.writeValueAsBytes(Map.of("position",pos,"companies",rows)));
        System.out.write(10);
        System.out.flush();
      }
    }
  }
}
"""
TYPES = {"aged-company":"aged","digital-company":"digital","resources":"resource","hydro":"hydro",
         "eng-a":"engineering","pharma":"research","north":"bank"}
INCOME = {"manufacturing":"营业收入（累计）","engineering":"营业收入（累计）","resource":"外部营业收入（累计）",
          "digital":"服务收入（累计）","aged":"终端售出收入（累计）","hydro":"售电收入（累计）"}
MEASURES = ("signedCustomerContracts", "deliveredUnits", "earnedSalesRevenue", "customerCollections",
            "customerPrepayments", "cashRefunds")
FIELDS = ("timeMillis","companyOperations","engineering","companyGovernance","research","roles","bank","accounts")
def vals(rows):
    return rows.values() if isinstance(rows,dict) else rows

def total(rows,key):
    return sum((r[key] for r in vals(rows)), Decimal(0))

def board_at(s,id):
    domain=s["engineering"] if id in s["engineering"]["boards"] else s["companyGovernance"]
    b=domain["boards"][id]["board"]
    meetings={k:{key:v[key] for key in ("topic","status","failureReason","attendeeSeatIds","ballots")}
              for k,v in b["meetings"].items()}
    periods=domain["periodic" if domain is s["engineering"] else "periods"]["companies"]
    periodic=next(q for q in vals(periods) if q["companyPartyId"]==id)
    terms=[a for a in s["roles"]["access"]["appointments"] if a["organizationPartyId"]==id]
    return {"seats":b["charter"]["seats"],"quorum":b["charter"]["quorum"],"chairSeatId":b["charter"]["chairSeatId"],
            "meetings":meetings,"budgets":b["budgets"],"commitments":b["commitments"],"roleTerms":terms,
            "periodicRecords":{k:periodic[k] for k in ("approvals","authorizations","scores","missed","releases")},
            "periodSnapshots":[{k:v[k] for k in ("period","capturedAt","position","statementDigest","basisDigest")}
                               for v in vals(periodic["snapshots"])]}

def business(s,id,bank,eng_direct,licensing):
    o=s["companyOperations"]; t=TYPES.get(id,"manufacturing")
    f=dict.fromkeys(MEASURES)
    extra={}
    if t=="manufacturing":
        l=next(q["ledger"] for q in o["companies"] if q["companyPartyId"]==id)
        f.update(signedCustomerContracts=len(l["contracts"]),deliveredUnits=total(l["contracts"],"delivered"),
                 customerCollections=total([r for r in o["reports"].values() if r["companyPartyId"]==id and r["accepted"] and r["action"]=="COLLECT"],"amount"))
    elif t=="digital":
        l=o[t]["ledger"]; c=l["contracts"]
        f.update(signedCustomerContracts=len(c),deliveredUnits=total(c,"delivered"),customerPrepayments=total(c,"prepaid"),
                 customerCollections=total(l["receivables"],"paid"),cashRefunds=total(c,"refunded"))
        extra.update(windDown=o[t].get("cessation"),grossRefundDue=sum((v["prepaid"]-v["prepaidApplied"]-v["refunded"] for v in c.values() if v["status"]=="TERMINATED"),Decimal(0)))
    elif t=="aged":
        l=o[t]["ledger"]; ch=l["channelLedgers"]
        f.update(deliveredUnits=total(l["sales"],"units"),customerPrepayments=total(ch,"prepaidReceived"),
                 customerCollections=total(ch,"collected"), cashRefunds=total(ch,"refundPaid"),
                 rawProcurementOrders=len(l["orders"]),terminalSales=len(l["sales"]))
    elif t=="resource":
        l=o[t]["ledger"]; r=l["receivables"]
        by_basis=Counter()
        for item in vals(r): by_basis[item["basis"]]+=item["units"]
        if set(by_basis)-{"COAL_KG","POWER_MWH"}: raise ValueError(f"unknown delivery unit {by_basis}")
        f.update(deliveredCoalKg=by_basis["COAL_KG"],deliveredPowerMwh=by_basis["POWER_MWH"],
                 customerCollections=total(r,"paid"),externalDeliveries=len(r))
    elif t=="hydro":
        plant=o[t]["ledger"]["plant"]
        f.update(deliveredKwh=plant["deliveredKilowattHours"],customerCollections=plant["collectedCash"])
    elif t=="engineering":
        l=s["engineering"]["companies"][id]
        payments=[p for p in s["engineering"]["customer"]["payments"] if p["companyPartyId"]==id and p["kind"]!="ADVANCE"]
        f.update(signedCustomerContracts=len(l["contracts"]),deliveredUnits=total(l["contracts"],"acceptedUnits"),
                 customerPrepayments=total(l["contracts"],"advanceReceived"),customerCollections=total(payments,"amount")+eng_direct)
    elif t=="research":
        if licensing:
            contracts=s["research"]["state"].get("licenses") or {}
            delivered=sum(c.get("deliveredAt") is not None for c in contracts.values())
            paid=sum(c.get("paidAt") is not None for c in contracts.values())
            fee=Decimal("300.00")   # approved-use-contract-v1 freezes one fee per delivered license
            f.update(signedCustomerContracts=len(contracts),deliveredLicenseRights=delivered,customerCollections=paid*fee)
            extra["outstandingLicenseReceivable"]=(delivered-paid)*fee
        projects=s["research"]["state"]["projects"]
        stages=[stage for p in vals(projects) for stage in p["stages"]]
        f.update(registeredResearchProjects=len(projects),paidResearchStages=sum(k["status"]!="PLANNED" for k in stages),
                 completedResearchStages=sum(k["status"]=="COMPLETED" for k in stages))
    elif t=="bank":
        f.update(bank)
        book=decode(s["bank"]["stateJson"])
        extra["loanPrincipalOnBook"]=sum((v["onbook"] for k,l in book["loans"].items() if k.startswith("L|5:north|") for v in l["tranches"]),Decimal(0))
    return {"cumulative":f,"atClose":extra,"board":board_at(s,id)}

def eng_no_cash_debt(s):
    l=s["engineering"]["companies"]["eng-a"]
    claims=vals(l["claims"])
    return sum((c["arCurrent"]+c["arRetention"]+c["memo"] for c in claims),Decimal(0))

def accepted_event(f, name):
    for e in f["events"]:
        if e["type"]==name:
            r=decode(e["payload"])
            if r["accepted"]:
                if r["position"]!=f["position"]: raise ValueError("receipt position")
                yield r

def linked_req(f):
    p=decode(next(e["payload"] for e in f["events"] if e["type"]=="TASK_COMPLETED_V1"))
    return decode(p["requestJson"]) if "requestJson" in p else p

def borrower_row(book, lender, borrower):
    return book["loans"].get(f"L|{len(lender)}:{lender}|{len(borrower)}:{borrower}", {})

def read_snapshots(path,cadence):
    h=hashlib.sha256()
    prev=hashlib.sha256(b"null").hexdigest()
    state={}; ends={}; quarters={}; lastday=None; lastquarter=None; final_time=0; ready=None; ids=None; run=None
    bank={k:Decimal(0) for k in ("draws","drawnPrincipal","repaidPrincipal","interestCashReceived","actualDeposits","actualWithdrawals")}
    vouchers=[]; dividend_meetings=set(); followup=None; loan_requests={}
    eng_direct=Decimal(0); snapshots={}; close_meta={}
    def capture(at):
        if at in snapshots:return
        snapshots[at] = decode(json.dumps({id:business(state,id,bank,eng_direct,licensing) for id in ids},default=str))
        if loan_losses:
            ledger=decode(state["bank"]["stateJson"])
            notes=(t for k,l in ledger["loans"].items() if k.startswith("L|5:north|") for t in l["tranches"])
            snapshots[at]["north"]["atClose"]["writtenOffPrincipalOutstanding"]=total(notes,"writtenOff")
        if issuer_dividends:
            book=state["dividends"]
            face=total(book["claims"],"face")-total(book["claims"],"paid")
            snapshots[at]["eng-a"]["atClose"].update(dividendFacePayable=face,
                holderReceivablesAfterImpairment=face-total(book["claims"],"impairment"))
        close_meta[at]={"timeMillis":state["timeMillis"],"accounts":len(state["accounts"]),
                        "cash":total(state["accounts"],"cash")}
    with path.open("rb") as sizes:
        for frame in checked_frames(path,h):
            pos=frame["position"]
            length=struct.unpack(">ii", sizes.read(8))[0]&0x7fffffff
            sizes.seek(length+32,1)
            if run is None:
                run=frame["runId"]
                manifest=checked_opening(frame) if run in OPENING_POLICIES else decode(next(e["payload"] for e in frame["events"] if e["type"]=="RUN_MANIFEST_V1"))
                licensing=manifest["manifest"]["dataVersions"].get("pharma-licensing")=="approved-use-contract-v1"
                issuer_dividends=manifest["manifest"]["dataVersions"].get("issuer-decisions")=="engineering-cash-dividend-v1"
                if issuer_dividends and run not in OPENING_POLICIES:
                    raise ValueError("发行人决策政策要求已登记的普通十公司版本")
                loan_losses = "bank-loan-loss" in OPENING_POLICIES.get(run,{})
                if loan_losses:
                    from audit_ten_company_run import checked_loan_receipt
                    bank.update(dict.fromkeys(("loanImpairmentAdded","loanPrincipalWrittenOff","writtenOffPrincipalRecovered","loanProvisionReleased"),Decimal(0)))
                fields=(*FIELDS,"dividends") if issuer_dividends else FIELDS
                if loan_losses:fields=(*fields,"bankActions")
                reg=decode(next(e["payload"] for e in frame["events"] if e["type"]=="OPENING_REGISTRY_V1"))
                ids={sec["issuerOrganizationId"]:sec["symbol"] for sec in reg["securities"]}
                if len(ids)!=10: raise ValueError("opening must have ten issuers")
            d=decode(next(e["payload"] for e in frame["events"] if e["type"]=="STATE_DELTA_V1"))
            if frame["runId"]!=run or d["runId"]!=run or d["basePosition"]!=pos-1 or d["position"]!=pos or d["baseDigest"]!=prev:raise ValueError("broken state link")
            prev=d["resultDigest"]; patch=d["patch"]
            if pos==1 and run in OPENING_POLICIES and "dividend-claims" in OPENING_POLICIES[run]:
                followup=dividend_followup_context(patch["value"],reg)
            if followup is not None:
                track_dividend_decisions(frame["events"],followup)
            now=patch["value"]["timeMillis"] if pos==1 else apply(final_time,patch["changes"].get("timeMillis",{"kind":"same"}))
            if now<final_time:raise ValueError("clock moved backwards")
            day=now//DAY
            quarter=max(0,(now-1)//(90*DAY))
            if cadence!="quarter" and lastday is not None and day!=lastday:
                capture(pos-1);ends[lastday]=(pos-1,previous_size)
            if cadence!="day" and lastquarter is not None and quarter!=lastquarter:
                capture(pos-1);quarters[lastquarter]=(pos-1,previous_size)
            bank_receipts=list(accepted_event(frame,"BANK_ACTION_V1"))
            losses=[]
            for event in frame["events"]:
                if event["type"]=="BANK_ACTION_V1":
                    v=decode(event["payload"])
                    if v["position"]!=pos:raise ValueError("bank action position")
                    if v["bankKey"]=="north":
                        if loan_losses and v["kind"] in ("LOAN_IMPAIR","LOAN_WRITE_OFF","LOAN_REPAY"):losses.append(v)
                        else:vouchers.append(v)
            er=list(accepted_event(frame,"ENGINEERING_REPORT_V1"))
            if issuer_dividends:
                board_receipt=dividend_board_receipt(frame,now,dividend_meetings)
                if board_receipt is not None:vouchers.append(board_receipt)
            funded_req=linked_req(frame) if er else {}
            action=funded_req.get("action")
            eng_before=eng_no_cash_debt(state) if action in ("COLLECT_DUE","COLLECT_RETENTION","RECOVER") else None
            if bank_receipts or losses:
                before=decode(state["bank"]["stateJson"])
                if loan_losses: old_cash={k:a["cash"] for k,a in state["accounts"].items()}
            dividend_frame=issuer_dividends and any(e["type"] in ("DIVIDEND_REPORT_V1","DIVIDEND_CASH_PAYMENT_V1") for e in frame["events"])
            if dividend_frame:
                pre_cash={k:a["cash"] for k,a in state["accounts"].items()}
                pre_claims={k:c.copy() for k,c in state["dividends"]["claims"].items()}
            if pos==1:
                state.update({k:patch["value"][k] for k in fields if k in patch["value"]})
            else:
                for k,delta in patch["changes"].items():
                    if k in fields:state[k]=apply(state.get(k),delta)
            if dividend_frame:
                cash={k:a["cash"] for k,a in state["accounts"].items()}
                vouchers.append(checked_dividend(frame,now,state["dividends"],pre_cash,cash,ids["eng-a"],pre_claims,followup))
            if followup is not None and "issuer-dividend-amendments" in OPENING_POLICIES.get(run,{}):
                from audit_ten_company_run import checked_amendment_notice
                notice=checked_amendment_notice(frame,now,followup)
                if notice:vouchers.append(notice)
            if eng_before is not None:
                # Client response receipts have their own customer payment records above.
                # Direct actions go through the funded task and extinguish this exact claim or memo.
                if any(r["kind"]=="ENGINEERING_FUND_V1" for r in er):
                    paid=eng_before-eng_no_cash_debt(state)
                    if paid<=0:raise ValueError("accepted engineering receipt without claim reduction")
                    eng_direct+=paid
            if bank_receipts or losses:
                after=decode(state["bank"]["stateJson"])
                for result in losses:
                    if result["atMillis"]!=now:raise ValueError("贷款回执与世界时点不符")
                    profile=decode(state["bank"]["configJson"])["profile"]
                    cash_same=old_cash=={k:a["cash"] for k,a in state["accounts"].items()}
                    entry,amounts=checked_loan_receipt(frame,result,before,after,profile,(state.get("bankActions") or {}).get("loans",{}),cash_same,loan_requests)
                    vouchers.append({**result,**entry})
                    for measure,value in amounts.items():bank[measure]+=value
                for r in bank_receipts:
                    if r["bankKey"]!="north":continue
                    kind=r["kind"]; req=linked_req(frame)
                    if r["customerKey"]!=req["customerKey"] or req["kind"]!=kind or req["bankKey"]!="north":raise ValueError("bank request/receipt mismatch")
                    old=borrower_row(before,"north",r["customerKey"]); new=borrower_row(after,"north",r["customerKey"])
                    principal=total(new.get("tranches",[]),"onbook")-total(old.get("tranches",[]),"onbook")
                    if loan_losses and kind in ("LOAN_IMPAIR","LOAN_WRITE_OFF","LOAN_REPAY"):
                        continue
                    elif kind in ("LOAN_DRAW","LOAN_REPAY"):
                        value=principal if kind=="LOAN_DRAW" else -principal
                        if value<=0 or not req["loanId"]:raise ValueError("bank receipt without loan movement")
                        bank["drawnPrincipal" if kind=="LOAN_DRAW" else "repaidPrincipal"]+=value
                        if kind=="LOAN_DRAW":bank["draws"]+=1
                    if kind=="LOAN_PAY_INTEREST":
                        # This transaction can accrue fresh interest before making payment.
                        # Use the gross posted receipt and its paid-cents subledger.
                        paid=lambda b:sum(v["terms"]["paidCents"] for v in b.get("tranches",[]) if v.get("terms"))
                        value=Decimal(paid(new)-paid(old))/100
                        receipt=dict(field.split("=",1) for field in r["receipt"].split(";"))
                        if value<=0 or value!=Decimal(receipt["amount"]):raise ValueError("interest receipt/loan paid amount mismatch")
                        bank["interestCashReceived"]+=value
                    if kind in ("DEPOSIT","WITHDRAW"):
                        oldgl=before["glByBank"]["north"]; newgl=after["glByBank"]["north"]
                        value=newgl["RESERVE"]-oldgl["RESERVE"]
                        value=value if kind=="DEPOSIT" else -value
                        if value<=0:raise ValueError("deposit receipt without reserve movement")
                        bank["actualDeposits" if kind=="DEPOSIT" else "actualWithdrawals"]+=value
            if ready is None and state.get("companyOperations") and state.get("research"):
                if now!=0:raise ValueError("opening baseline unavailable")
                ready=(pos,sizes.tell());capture(pos)
            previous_size=sizes.tell(); final_time=now;lastday=day;lastquarter=quarter
        if not ready:raise ValueError("no complete ordinary opening")
        capture(pos); ends[lastday]=quarters[lastquarter]=(pos,previous_size)
    return run,ids,ready,ends,quarters,snapshots,final_time,h.hexdigest(),vouchers,close_meta

def choose_windows(ends,ready,cutoff,cadence):
    windows=[]; close=ready; start=ready
    width=(90 if cadence=="quarter" else 1)*DAY
    last=max(0,(cutoff-1)//width) if cadence=="quarter" else cutoff//width
    for n in range(last+1):
        close=ends.get(n,close)
        windows.append((n,n*width,min((n+1)*width,cutoff),cutoff>=(n+1)*width,start,close))
        start=close
    return windows

def restore(frozen,journal,points):
    cpinfo=next((frozen/"target/surefire-reports").glob("TEST-*.xml"))
    cp=ET.parse(cpinfo).find(".//property[@name='java.class.path']").get("value").rstrip(os.pathsep)
    paths=[Path(s).resolve() for s in cp.split(os.pathsep)]
    if (frozen/"target/classes").resolve() not in paths: raise ValueError("test classpath is not this frozen build")
    java_home=Path(ET.parse(cpinfo).find(".//property[@name='java.home']").get("value"))
    suffix=".exe" if os.name=="nt" else ""
    with tempfile.TemporaryDirectory(prefix="company-statements-") as temp:
        t=Path(temp)
        (t/"Statements.java").write_text(JAVA,encoding="utf-8")
        (t/"cuts").write_text("".join(f"{pos} {size}\n" for pos,size in sorted(points)),encoding="ascii")
        subprocess.run([str(java_home/"bin"/("javac"+suffix)),"--release","17","-encoding","UTF-8","-cp",cp,
                        "Statements.java"],cwd=t,check=True)
        p=subprocess.run([str(java_home/"bin"/("java"+suffix)),"-Xmx1500m","-Dfile.encoding=UTF-8","-cp",
                          str(t)+os.pathsep+cp,"Statements",str(journal.resolve()),"cuts"],cwd=t,check=True,
                         stdout=subprocess.PIPE)
    records=[decode(l) for l in p.stdout.decode("utf-8").splitlines() if l.strip()]
    ret={r["position"]:r["companies"] for r in records}
    if set(ret)!={p for p,_ in points}:raise ValueError("missing official close")
    return ret

def metricdiff(after,before):
    if after is None and before is None: return None
    if before is None or after is None:raise ValueError("coverage changed during interval")
    return Decimal(str(after))-Decimal(str(before))

def report(args):
    run,ids,ready,ends,quarters,snap,time_ms,sha,vouchers,close_meta=read_snapshots(args.journal,args.cadence)
    windows={c:choose_windows(endpoints,ready,time_ms,c) for c,endpoints in (("day",ends),("quarter",quarters))
             if args.cadence in ("both",c)}
    points={p for ws in windows.values() for *_,a,b in ws for p in (a,b)}
    official=restore(args.frozen,args.journal,points)
    for pos,rows in official.items():
        for id,r in rows.items():
            if not r["balanced"] or r["assets"]!=r["liabilities"]+r["equity"]:raise ValueError(f"{pos}/{id}: A!=L+E")
            t=TYPES.get(id,"manufacturing")
            if t in INCOME:snap[pos][id]["cumulative"]["earnedSalesRevenue"]=r["report"]["income"][INCOME[t]]
            if t=="research" and "许可收入（累计）" in r["report"]["income"]:
                research=snap[pos][id]
                revenue=r["report"]["income"]["许可收入（累计）"]
                receivable=r["report"]["assets"]["许可应收款"]
                if Decimal(revenue) != Decimal(research["cumulative"]["customerCollections"])+Decimal(research["atClose"]["outstandingLicenseReceivable"]) or Decimal(receivable)!=Decimal(research["atClose"]["outstandingLicenseReceivable"]):
                    raise ValueError(f"{pos}/{id}: license income, receivable and collections differ")
                research["cumulative"]["earnedSalesRevenue"]=revenue
            if t=="bank":snap[pos][id]["cumulative"]["bankInterestIncome"]=r["report"]["income"]["INT.INC"]
    out={}
    for cadence,ws in windows.items():
        records=[]
        for n,start_ms,end_ms,complete,start,close in ws:
            p0=start[0];p1=close[0]
            for id,symbol in ids.items():
                a=snap[p0][id];b=snap[p1][id];board=b["board"]; flows=b["cumulative"]
                changes={k:metricdiff(v,a["cumulative"][k]) for k,v in flows.items()}
                b0=a["board"]["meetings"];b1=board["meetings"]
                moves={k:{"previous":b0.get(k,{}).get("status"),"atClose":v["status"],"topic":v["topic"],
                           "newBallots":[s for s,z in v["ballots"].items() if b0.get(k,{}).get("ballots",{}).get(s)!=z]}
                       for k,v in b1.items() if v!=b0.get(k)}
                # At a completed right-open boundary, an appointment ending exactly there
                # still covers the final instant of the reported interval.
                at=end_ms-1 if complete and cadence=="day" else end_ms
                active=[q for q in board["roleTerms"] if q["startsAt"]<=at<q["endsAt"] and
                        (q["revokedAt"] is None or at<q["revokedAt"])]
                specific={"bankActionReceipts":[v for v in vouchers if "bankKey" in v and p0<v["position"]<=p1]} if id=="north" else {}
                if id=="eng-a" and "dividendFacePayable" in b["atClose"]:
                    divs=[v for v in vouchers if v.get("issuer")==id and p0<v["position"]<=p1]
                    specific["issuerDividendReceipts"]=divs
                    changes.update(registeredDividendFace=sum(v.get("registeredFace",0) for v in divs),
                                   holderDividendReceivablesBooked=sum(v.get("receivablesBooked",0) for v in divs),
                                   cashDividendsPaid=sum(v.get("cashPaid",0) for v in divs))
                    if "dividend-claims" in OPENING_POLICIES.get(run,{}):
                        changes.update(holderDividendImpairmentAdded=sum(v.get("claimImpairmentAdded",0) for v in divs),
                                       holderDividendImpairmentReleased=sum(v.get("claimImpairmentReleased",0) for v in divs))
                records.append({"period":n+1,"startMillis":start_ms,"throughMillis":end_ms,
                    "complete":complete,"openingPosition":p0,"closingPosition":p1,"issuer":id,"symbol":symbol,
                    "template":TYPES.get(id,"manufacturing"),"flows":changes,"otherClosingStocks":b["atClose"],
                    "board":{"meetingChanges":moves,"atClose":board,"activeAppointments":active,
                             "activeCompanyManagers":[q["personPartyId"] for q in active if q["role"]=="COMPANY_MANAGER"]},
                    "statementPosition":p1,**specific})
        out[cadence]=records
    with args.journal.open("rb") as original:
        if hashlib.file_digest(original,"sha256").hexdigest()!=sha:raise ValueError("input changed")
    return {"runId":run,"journalSha256":sha,"frozenBuild":str(args.frozen.resolve()),"cutoffTimeMillis":time_ms,
            "openingPosition":ready[0],"source":str(args.journal.resolve()),
            "confirmedAtPosition":close_meta,
            "basis":{"window":"Daily buckets use [start,end); an event at midnight belongs to the new day. Preregistered D90/D180 cutoffs include all confirmed frames with time <= cutoff, even at exactly midnight. Each quarter compares its two closing snapshots, excluding changes already included at its start. No extra quarter at exactly D90 or D180. Final partial windows include their cutoff. An eventless interval carries the last confirmed frame. Opening positions are excluded from flows.",
            "null":"Not applicable: no corresponding transaction type or unit for this template. Zero means observed applicable ledger with no net flow. See statements unsupported/basis for limits.",
            "receipts":"Customer collections exclude cash prepayments. Earned sales revenue excludes borrowings, investment and deposit interest. Bank uses actual confirmed actions and GL/principal movements. A bank's interest/facility accounts are separate from customer sales.",
            "authority":"Administrative observation of charter seats, native meetings and ballots, periodic authorizations, budgets and commitments, plus appointment validity at interval close. Empty manager list means no active COMPANY_MANAGER appointment; directors and bank roles remain identified separately. Each accepted transaction still undergoes tool-specific and amount checks.",
            "templates":"Signed customer contracts for engineering, manufacturing and digital. Aged procurement orders and terminal sales, resource kg and MWh, hydro kWh, research registered projects and paid/completed phases have separate columns; no cross-unit totals.",
            "statements":"Keyed by journal position. Balances and line items use the official ComposedCompanyReport or BankCompanyReport incl. contra and accumulated earnings. Directional flows are period changes of confirmed cumulative quantities; preexisting loan principal is stock, never counted as a new draw."},
            "rows":out,"statementsByPosition":official}

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("journal",type=Path);p.add_argument("--frozen",type=Path,required=True)
    p.add_argument("--cadence",choices=("both","quarter","day"),default="both")
    p.add_argument("--output",type=Path,help="UTF-8 JSON, new file only; default stdout")
    args=p.parse_args()
    data=report(args)
    if args.output:
        with args.output.open("x",encoding="utf-8") as out:
            json.dump(data,out,ensure_ascii=False,default=str);out.write("\n")
    else:
        sys.stdout.reconfigure(encoding="utf-8")
        print(json.dumps(data,ensure_ascii=False,default=str))
