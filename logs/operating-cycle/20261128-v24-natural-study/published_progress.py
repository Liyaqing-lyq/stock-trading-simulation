"""监视活跃运行已正式发布的状态和物理文件，不打开 world.journal。"""
from contextlib import closing
import hashlib
import json
import re
import sqlite3

from live_sample import IDENTITY, complete_json


def observe_input(db, number, call):
    view=json.loads(call["inputText"]);who=view["person"];now=view["time"];e=view["engineering"]
    interview=view.get("interview") or {}
    focus=interview.get("activeTurn")
    if focus:
        db.execute("INSERT INTO interview_inputs VALUES(?,?,?,?,?,?)",
                   (number,who,focus["requestId"],now,focus["deadlineMillis"],focus.get("nextTurnTool")))
    for session in interview.get("sessions",[]):
        for turn in session.get("turns",[]):
            db.execute("INSERT OR IGNORE INTO interview_turns VALUES(?,?,?,?,?,?)",
                       (session["sessionId"],turn["turnId"],turn["speakerPartyId"],turn["docId"],turn["docVersion"],turn["publishedAtMillis"]))
    for doc in view["profile"]["readExperiences"]:
        db.execute("INSERT OR IGNORE INTO reads VALUES(?,?,?,?,?)",(who,doc["docId"],doc["version"],doc["completedAt"],doc["title"]))
    for old in e.get("deliberation",{}).get("recentDecisions",[]):
        if old.get("outcome")=="TECHNICAL_FAILED":
            details={"actor":who,"actionId":old["actionId"],"protocolReason":old.get("protocolReason")}
            db.execute("INSERT INTO firsts VALUES('technicalFailureSeenByActor',?,?,?) ON CONFLICT(kind) DO UPDATE SET "
                       "n=excluded.n,at=excluded.at,detail=excluded.detail WHERE excluded.n<firsts.n",(number,now,json.dumps(details)))
        if old.get("accepted") is None or old.get("resultAt") is None or not old.get("tool"):continue
        if old["tool"].startswith(("media.","board.")):
            db.execute("INSERT OR IGNORE INTO feedback VALUES(?,?,?,?,?)",(old["actionId"],who,old["tool"],int(old["accepted"]),old["resultAt"]))
    if e.get("exchangeSessions",{}).get("sessionOpen"):
        db.execute("INSERT INTO firsts VALUES('session',?,?,?) ON CONFLICT(kind) DO UPDATE SET n=excluded.n,"
                   "at=excluded.at,detail=excluded.detail WHERE excluded.n<firsts.n",(number,now,json.dumps({"actor":who})))
    for q in view["profile"]["market"]:
        if q.get("bestBid") is not None or q.get("bestAsk") is not None:
            db.execute("INSERT INTO firsts VALUES('quote',?,?,?) ON CONFLICT(kind) DO UPDATE SET n=excluded.n,"
                       "at=excluded.at,detail=excluded.detail WHERE excluded.n<firsts.n",(number,now,json.dumps(
                       {k:q.get(k) for k in ("symbol","bestBid","bestAsk","bestBidQuantity","bestAskQuantity")})))
            break


def inspect(path,source,cfg,run_id,status,cache,reason):
    if cache.resolve().is_relative_to(path.resolve()):raise ValueError("采样缓存须在样本目录之外")
    cache.parent.mkdir(parents=True,exist_ok=True)
    with closing(sqlite3.connect(cache)) as db,db:
        stat=(path/"config.json").stat()
        source_id=json.dumps([str(path.resolve()),stat.st_dev,stat.st_ino,hashlib.sha256((path/"config.json").read_bytes()).hexdigest()])
        db.execute("CREATE TABLE IF NOT EXISTS segment(id TEXT PRIMARY KEY)")
        old=db.execute("SELECT id FROM segment").fetchone()
        if old and old[0]!=source_id:raise ValueError("样本配置文件身份有变，请使用新缓存核验")
        if not old:db.execute("INSERT INTO segment VALUES(?)",(source_id,))
        db.execute("CREATE TABLE IF NOT EXISTS files (n INTEGER, kind TEXT, identity TEXT, aid TEXT, providerId TEXT, failure TEXT,"
                   " PRIMARY KEY(n,kind),UNIQUE(kind,aid))")
        db.execute("CREATE TABLE IF NOT EXISTS reads(person TEXT,doc TEXT,version INTEGER,at INTEGER,title TEXT,PRIMARY KEY(person,doc,version))")
        db.execute("CREATE TABLE IF NOT EXISTS feedback(action TEXT PRIMARY KEY,person TEXT,tool TEXT,accepted INTEGER,at INTEGER)")
        db.execute("CREATE TABLE IF NOT EXISTS interview_inputs(n INTEGER PRIMARY KEY,person TEXT,request TEXT,observed INTEGER,deadline INTEGER,tool TEXT)")
        db.execute("CREATE TABLE IF NOT EXISTS interview_turns(session TEXT,turn TEXT,speaker TEXT,doc TEXT,version INTEGER,published INTEGER,PRIMARY KEY(session,turn))")
        db.execute("CREATE TABLE IF NOT EXISTS firsts(kind TEXT PRIMARY KEY,n INTEGER,at INTEGER,detail TEXT)")
        seen=set(db.execute("SELECT n,kind FROM files"));bad=[]
        names=[]
        for f in path.iterdir():
            m=re.fullmatch(r"(call|reply|provider)-(\d+)\.json",f.name)
            if m and (int(m[2]),m[1]) not in seen:names.append((int(m[2]),m[1],f))
        for n,kind,f in sorted(names):
            item=complete_json(f)
            if item is None:
                bad.append(f.name);continue
            rec=item["reply"] if kind=="reply" else item
            ident=json.dumps([rec[k] for k in IDENTITY],ensure_ascii=False)
            if rec["runId"]!=run_id:raise ValueError(f"第 {n} 笔运行身份不符")
            same=db.execute("SELECT kind,identity,failure FROM files WHERE n=?",(n,)).fetchall()
            if any(other!=ident or (kind!="call" and typ!="call" and code!=item.get("failureCode")) for typ,other,code in same):
                raise ValueError(f"第 {n} 笔已发布文件的身份/失败码不符")
            if kind=="call":observe_input(db,n,item)
            db.execute("INSERT INTO files VALUES(?,?,?,?,?,?)",(n,kind,ident,rec["attemptId"],item.get("requestId") if kind=="provider" else None,item.get("failureCode")))
        counts=dict.fromkeys(("call","reply","provider"),0);counts.update(db.execute("SELECT kind,COUNT(*) FROM files GROUP BY kind"))
        counts["triples"]=db.execute("SELECT COUNT(*) FROM (SELECT n FROM files GROUP BY n HAVING COUNT(*)=3)").fetchone()[0]
        with_id=db.execute("SELECT COUNT(*) FROM files WHERE kind='provider' AND providerId IS NOT NULL").fetchone()[0]
        waiting=[x[0] for x in db.execute("SELECT c.aid FROM files c LEFT JOIN files r ON c.n=r.n AND r.kind='reply' "
                                          "WHERE c.kind='call' AND r.n IS NULL ORDER BY c.n")]
        partial=[x[0] for x in db.execute("SELECT n FROM files GROUP BY n HAVING COUNT(*)<3 ORDER BY n")]
        fail=db.execute("SELECT n,aid,failure FROM files WHERE kind='provider' AND failure IS NOT NULL ORDER BY n LIMIT 1").fetchone()
        read=db.execute("SELECT COUNT(*),COUNT(DISTINCT person) FROM reads").fetchone()
        recent=[dict(docId=doc,version=v,title=title,observedReaders=people,latestObservedReadingAt=at)
                for doc,v,title,people,at in db.execute("SELECT doc,version,title,COUNT(*),MAX(at) FROM reads GROUP BY doc,version ORDER BY MAX(at) DESC,doc LIMIT 12")]
        feedback=[dict(tool=t,accepted=bool(a),observedActions=c) for t,a,c in
                  db.execute("SELECT tool,accepted,COUNT(*) FROM feedback GROUP BY tool,accepted ORDER BY tool,accepted")]
        first={k:dict(observedInCall=n,observationTime=t,detail=json.loads(d)) for k,n,t,d in db.execute("SELECT * FROM firsts")}
        windows=db.execute("SELECT COUNT(*),COUNT(tool) FROM interview_inputs").fetchone()
        sample=db.execute("SELECT n,person,request,observed,deadline,tool FROM interview_inputs WHERE tool IS NOT NULL ORDER BY n LIMIT 1").fetchone()
        sample=dict(zip(("segmentCall","person","requestId","observedMillis","deadlineMillis","nextTurnTool"),sample)) if sample else None
        observed_turns=db.execute("SELECT COUNT(*) FROM interview_turns").fetchone()[0]
    return {"sourceCommit":source,"segment":str(path),"runId":run_id,
            "configuredScope":{k:cfg.get(k) for k in ("mode","persons","maxHttpCalls","wallMinutes","targetMillis")},
            "nativeJournalAvailability":reason,"journalPosition":None,"timeMillis":None,"statusFile":status,
            "completedDurableFilesThisSegment":counts,"providerRequestIdsThisSegment":with_id,
            "settledMatchingThisSegment":None,"sentLocalInputWithoutReplyAtSample":waiting,
            "replyWithoutMatchingWorldSettlementAtSample":None,"partialFileIndices":partial,"presentFilesNotFullyReadableAtSample":bad,
            "nativeDispatchesCumulative":None,"nativeOutcomesCumulative":None,"nativeDispatchWithoutConfirmedAuditAtSample":None,
            "firstConfirmedTechnicalFailureCumulative":None,"market":None,"actualReading":None,"mediaCompletionReceipts":None,"boardFinalReceipts":None,"interviewTaskCompletionReceipts":None,
            "releasedEvidence":{"firstProviderFailure":dict(segmentNumber=fail[0],attemptId=fail[1],code=fail[2]) if fail else None,
                                "personDocumentVersionsSeenRead":read[0],"peopleSeenWithReadExperience":read[1],
                                "recentDocumentEvidence":recent,"mediaAndBoardFeedbackSeenByActors":feedback,
                                "interviewEvidenceInCallInputs":{"activeTurnInputs":windows[0],"inputsWithNextTurnTool":windows[1],
                                                                "firstWithNextTool":sample,"distinctRecordedTurnsSeen":observed_turns},
                                "firstTechnicalFailureObservedByAnActor":first.pop("technicalFailureSeenByActor",None),"firstMarketViewsInInputs":first},
            "scope":"statusFile 是宿主自行发布的最近一次状态。物理文件按同一尝试关联；本机请求落盘先于外发。本人后续输入可证明当时看到的已读和业务回执，仅覆盖已派发的观察。未获可读原生帧时，逐笔世界结算、当下在途结算、全量业务与首个世界技术失败均暂不可核。"}
