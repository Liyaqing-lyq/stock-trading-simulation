"""当前段只读取样。可在另一个 PowerShell 重复执行，不连接世界服务。"""
import argparse
from contextlib import closing
from decimal import Decimal
import hashlib
import importlib.util
import json
from pathlib import Path
import sqlite3
import sys

import study
if __name__=="__main__" and len(sys.argv)>1 and not sys.argv[1].startswith("-"):
    frozen_sha=study.resolve_sha(sys.argv[1])
    sys.path.insert(0,str(study.CYCLE/("20261127-verification-"+frozen_sha[:7])/"frozen/scripts"))
if __name__=="__main__" and (len(sys.argv)==1 or sys.argv[1].startswith("-")):
    sys.path.insert(0,str(study.REPO/"scripts"))
import live_sample as live
import published_progress
from audit_ten_company_run import checked_opening, patch_field

here = Path(__file__).resolve().parent
module = importlib.util.spec_from_file_location("market_report",here.parent/"20261128-market-close-audit/report.py")
market = importlib.util.module_from_spec(module)
module.loader.exec_module(market)


def observe(frame, st, db):
    pos, now = st["position"], st["time"]
    if pos == 1:
        opening = checked_opening(frame)
        if opening["manifest"]["dataVersions"].get("exchange-sessions") != "weekday-hy1-v1":
            raise ValueError("不识别的交易日历")
        db.execute("CREATE TABLE attempts (id TEXT PRIMARY KEY, action TEXT, dispatch INTEGER, settled INTEGER, outcome TEXT)")
        db.execute("CREATE TABLE reads (doc TEXT, version INTEGER, title TEXT, person TEXT, n INTEGER, lastPos INTEGER,"
                   "PRIMARY KEY(doc,version,person))")
        st["progress"] = {"media":{},"meetings":{},"interviews":{},"firstFailure":None,"firstRead":None,"firstQuote":None,
                          "firstConfirmedOpenFrame":None,"book":{}}
    p = st["progress"]
    events = frame["events"]
    patch = live.decode(next(e["payload"] for e in events if e["type"]=="STATE_DELTA_V1"))["patch"]
    changes = patch.get("changes",{}).get("exchange")
    if pos == 1 or changes:
        if pos == 1 or changes["kind"] == "replace":
            ex = patch["value"]["exchange"] if pos==1 else changes["value"]
            p["orders"],p["closed"] = ex["orders"],ex["closed"]
        elif changes["kind"] == "object":
            for name in ("closed","orders"):
                if name in changes["changes"]:p[name]=patch_field(p[name],changes["changes"][name])
        elif changes["kind"] != "same":raise ValueError("未知交易所差量")
        for order in p["orders"]:
            if isinstance(order.get("limitPrice"),str):order["limitPrice"]=Decimal(order["limitPrice"])
        p["book"] = market.compact_book({"orders":p["orders"]},st["issuers"])
        if p["book"] and p["firstQuote"] is None:p["firstQuote"]={"position":pos,"timeMillis":now,"book":p["book"]}
    if p["firstConfirmedOpenFrame"] is None and market.phase(now,p["closed"])=="OPEN":
        p["firstConfirmedOpenFrame"]={"position":pos,"timeMillis":now}
    for e in events:
        k = e["type"]
        if k=="DECISION_DISPATCH_V1":
            a=live.decode(e["payload"])
            db.execute("INSERT INTO attempts(id,action,dispatch) VALUES(?,?,?)",(a["attemptId"],a["actionId"],pos))
        elif k=="DECISION_AUDIT_V1":
            a=live.decode(e["payload"])
            changed=db.execute("UPDATE attempts SET settled=COALESCE(settled,?), outcome=COALESCE(outcome,?) WHERE id=? AND action=?",
                               (pos,a["outcome"],a["attemptId"],a["actionId"])).rowcount
            if not changed:raise ValueError("决定回执缺少相同身份的原生派发")
            if a["outcome"].startswith("TECHNICAL_FAILED") and p["firstFailure"] is None:
                p["firstFailure"]={k:a[k] for k in ("position","actionId","attemptId","outcome","kind")}
                p["firstFailure"]["timeMillis"]=now
        elif k==live.READ:
            a=live.decode(e["payload"])
            if p["firstRead"] is None:p["firstRead"]={k:a[k] for k in ("personPartyId","docId","version","completedAt")}
            db.execute("INSERT INTO reads VALUES(?,?,?,?,1,?) ON CONFLICT(doc,version,person) DO UPDATE "
                       "SET n=n+1,lastPos=excluded.lastPos",(a["docId"],a["version"],a["title"],a["personPartyId"],pos))
        elif k=="MEDIA_TASK_V1":
            a=live.decode(e["payload"]);live.inc(p["media"],a["kind"]+"/"+frame["result"])
        elif k=="INTERVIEW_TASK_V1":
            a=live.decode(e["payload"]);live.inc(p["interviews"],a["kind"]+"/"+frame["result"])
        elif k=="COMPANY_BOARD_REPORT_V1":
            a=live.decode(e["payload"])
            if a["reason"]!="STARTED":
                task=live.decode(next(x["payload"] for x in events if x["type"]=="TASK_COMPLETED_V1"))
                body=live.decode(task["payload"]) if "payload" in task else task
                live.inc(p["meetings"],a["company"]+"/"+body["action"]+"/"+a["reason"])


def inspect(sha,path):
    path = path.resolve(strict=True)
    source = study.resolve_sha(sha)
    study.need(path.parent==here/source,"进度目录与指定正式卷不同")
    cfg = live.complete_json(path/"config.json")
    if cfg is None:
        if (path/"result.json").is_file():raise ValueError("已有结果但样本配置缺失或无法解析")
        return {"sourceCommit":source,"segment":str(path),"state":"等待完整 config.json"}
    if cfg["sourceCommit"]!=source:raise ValueError("样本配置的源码 SHA 与参数不符")
    cache = here/"monitor-cache"/("progress-v3-"+hashlib.sha256((source+str(path)).encode()).hexdigest()+".sqlite")
    status=live.complete_json(path/"status.json")
    def published(reason):
        return published_progress.inspect(path,source,cfg,study.RUNS[cfg["mode"]],status,
                                          cache.with_suffix(".published-v2.sqlite"),reason)
    if live.complete_json(Path(str(path)+".exit.json")) is None:
        return published("ACTIVE_WRITER_USING_PUBLISHED_FILES")
    try:
        s=live.sample_once(path,cache,observe)
    except PermissionError as failure:
        if (not failure.filename or Path(failure.filename).resolve()!=(path/"world.journal").resolve()
                or live.complete_json(Path(str(path)+".exit.json")) is not None):raise
        return published("NATIVE_JOURNAL_READ_DENIED")
    if not s.get("position"):return {"sourceCommit":source,"segment":str(path),"confirmedPosition":0,"status":s.get("statusFile")}
    if study.RUNS[cfg["mode"]]!=s["runId"]:raise ValueError("配置模式与原生运行身份不符")
    with closing(sqlite3.connect(cache.resolve().as_uri()+"?mode=ro",uri=True)) as db:
        st=json.loads(db.execute("SELECT data FROM checkpoint WHERE id=1").fetchone()[0]);p=st["progress"]
        prefix=st["receiptIndex"]; provider_ids=st.get("identifiedProviderReceipts",0)
        counts={k:prefix for k in ("call","reply","provider","triples")}
        settled=db.execute("SELECT COUNT(*) FROM receipt_ids r JOIN attempts a ON a.id=r.id WHERE a.settled IS NOT NULL").fetchone()[0]
        local_pending=db.execute("SELECT r.id FROM receipt_ids r LEFT JOIN attempts a ON a.id=r.id WHERE a.settled IS NULL").fetchall()
        awaiting_world=[x[0] for x in local_pending]; awaiting_reply=[]; partial=[]; unreadable=[]
        indices={int(f.stem.split("-")[-1]) for f in path.glob("*-*.json")
                 if f.stem.split("-")[0] in ("call","reply","provider") and f.stem.split("-")[-1].isdigit()
                 and int(f.stem.split("-")[-1])>prefix}
        first=p["firstFailure"]
        if first:
            row=db.execute("SELECT rowid FROM receipt_ids WHERE id=?",(first["attemptId"],)).fetchone()
            first={**first,"segmentNumber":row[0] if row else None}
        for n in sorted(indices):
            group={k:live.complete_json(path/f"{k}-{n}.json") for k in ("call","reply","provider")}
            for k,v in group.items():
                filename=f"{k}-{n}.json"
                if v is None and (path/filename).exists():unreadable.append(filename)
            for k,v in group.items():counts[k]+=int(v is not None)
            if not group["call"]:
                partial.append(n);continue
            call=group["call"];aid=call["attemptId"];identity=tuple(call[k] for k in live.IDENTITY)
            for other in (group["reply"]["reply"] if group["reply"] else None,group["provider"]):
                if other and tuple(other[k] for k in live.IDENTITY)!=identity:raise ValueError(f"第 {n} 笔身份不符")
            if call["runId"]!=s["runId"]:raise ValueError("请求运行身份不符")
            if all(group.values()):
                if group["reply"].get("failureCode")!=group["provider"].get("failureCode"):raise ValueError("返回失败码不同")
                counts["triples"]+=1
            if group["provider"] and group["provider"].get("requestId"):provider_ids+=1
            row=db.execute("SELECT action,settled FROM attempts WHERE id=?",(aid,)).fetchone()
            if row and row[0]!=call["actionId"]:raise ValueError("原生派发和请求动作不同")
            if row and row[1] is not None:settled+=1
            elif group["reply"]:awaiting_world.append(aid)
            if not group["reply"]:awaiting_reply.append(aid)
            if first and aid==first["attemptId"]:first["segmentNumber"]=n
        native_pending=[x[0] for x in db.execute("SELECT id FROM attempts WHERE settled IS NULL ORDER BY dispatch")]
        doc_count=db.execute("SELECT COUNT(*) FROM (SELECT doc,version FROM reads GROUP BY doc,version)").fetchone()[0]
        documents=[dict(docId=doc,version=version,title=title,completions=count,distinctReaders=people,lastPosition=last)
                   for doc,version,title,count,people,last in db.execute(
                       "SELECT doc,version,title,SUM(n),COUNT(*),MAX(lastPos) FROM reads GROUP BY doc,version "
                       "ORDER BY MAX(lastPos) DESC,doc,version LIMIT 12")]
    result=live.complete_json(path/"result.json")
    seal=live.complete_json(Path(str(path)+".strict-reopen.json"))
    exit_file=live.complete_json(Path(str(path)+".exit.json"))
    if exit_file and exit_file.get("code")==0:
        if result is None or unreadable or any(n!=result["httpCalls"] for n in counts.values()):
            raise ValueError(f"正常退出段的完整输入/回执与结果计数不一致：{counts}, 无法解析：{unreadable}")
    return {"sourceCommit":source,"segment":str(path),"runId":s["runId"],
            "configuredScope":{k:cfg.get(k) for k in ("mode","persons","maxHttpCalls","wallMinutes","targetMillis")},
            "nativeJournalAvailability":"VERIFIED_COMPLETE_PREFIX","journalPosition":s["position"],
            "timeMillis":s["timeMillis"],"unconfirmedTrailingBytesAtSample":s["pendingTailBytesAtOpen"],"statusFile":s["statusFile"],
            "completedDurableFilesThisSegment":counts,"providerRequestIdsThisSegment":provider_ids,
            "settledMatchingThisSegment":settled,"sentLocalInputWithoutReplyAtSample":awaiting_reply,
            "replyWithoutMatchingWorldSettlementAtSample":awaiting_world,"partialFileIndices":partial,
            "presentFilesNotFullyReadableAtSample":unreadable,
            "nativeDispatchesCumulative":s["nativeDispatches"],"nativeOutcomesCumulative":s["decisionOutcomes"],
            "nativeDispatchWithoutConfirmedAuditAtSample":native_pending,"firstConfirmedTechnicalFailureCumulative":first,
            "market":{"firstScheduledOpenMillis":570*market.MINUTE,"phaseNow":market.phase(s["timeMillis"],p["closed"]),
                      "firstConfirmedOpenFrame":p["firstConfirmedOpenFrame"],"firstConfirmedRestingQuote":p["firstQuote"],
                      "currentRestingQuotes":p["book"],"orderReports":s["orderReports"],"executedTrades":s["trades"]},
            "actualReading":{"completed":s["completedReads"],"distinctPeople":s["peopleWithCompletedReads"],"first":p["firstRead"],
                             "distinctDocumentVersions":doc_count,"twelveMostRecentlyCompletedDocumentVersions":documents,
                             "repeatedCompletions":sum(d.get("completedRereads",0) for d in s["byDay"].values())},
            "mediaCompletionReceipts":p["media"],"boardFinalReceipts":p["meetings"],"interviewTaskCompletionReceipts":p["interviews"],
            "sealedReport":{"resultFilePresent":result is not None,"strictReopenFilePresent":seal is not None,
                            "probeStop":result.get("probeStop") if result else None},
            "scope":"供应商请求 ID 证明对端收到；本机请求文件先于发出。原生累计含续段继承；文件按本段统计。取样非原子，尾部在途允许下一次变化。最终逐笔一致性、真实退出及恢复在封卷后另核；供应商未提供的费用仍为 UNKNOWN。"}


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sha");parser.add_argument("segment",type=Path);args=parser.parse_args()
    print(json.dumps(inspect(args.sha,args.segment),indent=2,ensure_ascii=False,default=str))
