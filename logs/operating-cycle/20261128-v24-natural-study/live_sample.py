"""只读采样已确认帧；本地 SQLite 仅保存本次采样结果和决定身份索引。"""
import argparse
from contextlib import closing
from decimal import Decimal
import gzip
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import struct
import sys

ROOT = Path(__file__).resolve().parents[3]
if __name__=="__main__":
    if len(sys.argv)>1 and not sys.argv[1].startswith("-"):
        from study import EDITORIAL_SOURCE_MODE, STUDY, CYCLE, resolve_sha
        sample_path=Path(sys.argv[1]).resolve()
        config=json.loads((sample_path/"config.json").read_bytes())
        source=resolve_sha(config["sourceCommit"])
        if config["mode"]!=EDITORIAL_SOURCE_MODE or sample_path.parent!=STUDY/source:raise ValueError("不是本驱动的正式 V24 样本")
        sys.path.insert(0,str(CYCLE/("20261127-verification-"+source[:7])/"frozen/scripts"))
    else:sys.path.insert(0,str(ROOT/"scripts"))
from audit_ten_company_run import DAY, ORDER, READ, TRADE, OPENING_POLICIES, checked_opening, checked_reread, decode, opening_people, patch_field

IDENTITY = ("runId", "actionId", "attemptId", "attemptNo", "recoveryGeneration")
NULL_DIGEST = hashlib.sha256(b"null").hexdigest()


def check(ok, why):
    if not ok:
        raise ValueError(why)


def inc(obj, key, amount=1):
    obj[key] = obj.get(key, 0) + amount


def complete_json(path):
    try:
        return json.loads(path.read_bytes())
    except (FileNotFoundError, PermissionError, UnicodeDecodeError, json.JSONDecodeError):
        return None


class JournalReadDenied(PermissionError):
    """只标记原生账本上的 Windows 读锁，缓存和其他文件出错仍原样报错。"""


def journal_read(stream, size):
    try:
        return stream.read(size)
    except PermissionError as failure:
        raise JournalReadDenied(failure.errno, str(failure), stream.name) from failure


def next_frame(stream, limit):
    start = stream.tell()
    if limit - start < 8:
        return None
    length, complement = struct.unpack(">ii", journal_read(stream,8))
    check(complement == ~length, f"帧头校验错误，偏移 {start}")
    actual = length & 0x7fffffff
    if limit - stream.tell() < actual + 32:
        return None
    payload = journal_read(stream,actual)
    trailer = journal_read(stream,32)
    check(len(trailer) == 32 and hashlib.sha256(payload).digest() == trailer, f"完整帧校验失败，偏移 {start}")
    return decode(gzip.decompress(payload) if length < 0 else payload), trailer.hex()


def get_owner(db, action):
    row = db.execute("SELECT person FROM actions WHERE id=?", (action,)).fetchone()
    check(row is not None, f"派发缺少已确认的决定身份 {action}")
    return row[0]


def initial():
    return dict(position=0, offset=0, digest=NULL_DIGEST, time=0, receiptIndex=0,
                days={}, people={}, symbols={}, receiptOutcomes={}, audits={}, providerUsage={})


def scheduled_tool(patch, action_id, event):
    ref=patch
    for key in ("decisions","actionRefs",action_id):
        if ref["kind"]=="replace":
            ref={"kind":"replace","value":ref["value"][key]}
        else:
            check(ref["kind"]=="object" and key in ref["changes"],"业务排程缺少正式决定工具引用")
            ref=ref["changes"][key]
    value=patch_field({},ref)
    check(value["actionId"]==action_id and event["eventId"]=="dec-scheduled:"+value["taskId"],
          "正式工具引用与已排程的动作或待办不同")
    return value["tool"]


def ingest(frame, st, db):
    events = frame["events"]
    pos = st["position"] + 1
    deltas = [e for e in events if e["type"] == "STATE_DELTA_V1"]
    check(len(deltas) == 1, "每帧须有一份状态差量")
    d = decode(deltas[0]["payload"])
    run = frame["runId"]
    check(frame["position"] == pos == d["position"] and d["basePosition"] == pos - 1
          and d["baseDigest"] == st["digest"] and d["runId"] == run and d["version"] == "delta-v1",
          f"帧 {pos} 序列或相邻状态摘要错误")
    patch = d["patch"]
    if pos == 1:
        check(run in OPENING_POLICIES,
              "仅接受已有合同的标准十公司运行")
        manifest = checked_opening(frame)["manifest"]
        data = manifest["dataVersions"]
        check(manifest["runId"] == patch["value"]["runId"] == data["branch-profile-run"] == run, "开局标识不符")
        s = patch["value"]
        registry, = [decode(e["payload"]) for e in events if e["type"] == "OPENING_REGISTRY_V1"]
        st.update(run=run, policies=dict(OPENING_POLICIES[run]), accounts=s["accounts"],
                  natural=opening_people(registry,s["accounts"],s["securities"]["lots"])["naturalPersons"],
                  issuers={x["symbol"]:x["issuerOrganizationId"] for x in registry["securities"]})
        now = s["timeMillis"]
    else:
        check(st["run"] == run, "样本运行身份变化")
        changes = patch["changes"]
        now = patch_field(st["time"], changes["timeMillis"]) if "timeMillis" in changes else st["time"]
        if "accounts" in changes:
            st["accounts"] = patch_field(st["accounts"],changes["accounts"])
    check(now >= st["time"], "时钟倒退")
    day = st["days"].setdefault(str(now // DAY), {"firstPosition":pos,"events":{},"roleActions":{}})
    day["lastPosition"] = pos
    for e in events:
        k = e["type"]
        inc(day["events"], k)
        if k == "DECISION_OPENED_V1":
            for action in decode(e["payload"]):
                db.execute("INSERT INTO actions VALUES(?,?)", (action["actionId"],action["personPartyId"]))
        elif k in {"DECISION_DISPATCH_V1", READ, "DECISION_ACTION_SCHEDULED_V1"}:
            data = decode(e["payload"])
            if k == "DECISION_DISPATCH_V1":
                person, name = get_owner(db,data["actionId"]), "dispatches"
            elif k == READ:
                if checked_reread(frame,e["payload"],data,now,st.setdefault("readIds",{})):
                    inc(day,"completedRereads")
                person, name = data["personPartyId"], "completedReads"
            else:
                action_id, = [decode(a["payload"])["actionId"] for a in events if a["type"] == "DECISION_AUDIT_V1"]
                person, name = get_owner(db,action_id), "scheduledActions"
                inc(day["roleActions"], data.get("kind") or data.get("targetKind") or scheduled_tool(patch,action_id,e))
            inc(st["people"].setdefault(person,{}), name)
        elif k == "DECISION_AUDIT_V1":
            data = decode(e["payload"])
            check((data["runId"],data["position"],data["outcome"]) == (run,pos,frame["result"]), "决定审计与帧不符")
            inc(st["audits"], data["kind"]+"/"+data["outcome"])
        elif k == ORDER:
            data = decode(e["payload"])
            inc(day.setdefault("orderReports",{}), data["kind"]+"/"+str(data["accepted"]))
        elif k == TRADE:
            t = decode(e["payload"])
            check(t["timeMillis"] == now and t["quantity"] > 0 and t["price"] > 0, "成交时点、数量或金额不符")
            symbol = st["symbols"].setdefault(t["symbol"],{})
            inc(symbol,"fills"); inc(symbol,"quantity",t["quantity"])
            symbol["turnover"] = str(Decimal(symbol.get("turnover","0"))+t["price"]*t["quantity"])
        elif k in {"CAPITAL_FLOW_V1","RESOURCE_PRIMARY_CAPITAL_V1"}:
            flow=decode(e["payload"])
            check(flow["atMillis"] == now and flow["position"] == pos and flow["amount"] > 0, "实缴资本流与时点不符")
            day["capitalPaid"] = str(Decimal(day.get("capitalPaid","0"))+flow["amount"])
    cash = sum((Decimal(a["cash"]) for a in st["accounts"].values()),Decimal(0))
    if pos == 1:
        st["openingCash"] = str(cash)
    day["closingCash"] = str(cash)
    st.update(position=pos,time=now,digest=d["resultDigest"])


def receipts(sample, st, db):
    while True:
        n=st["receiptIndex"]+1
        group=[complete_json(sample/f"{prefix}-{n}.json") for prefix in ("call","reply","provider")]
        if any(v is None for v in group):
            return
        call, wrapped, provenance = group
        reply=wrapped["reply"]
        identity=tuple(call[k] for k in IDENTITY)
        check(all(tuple(v[k] for k in IDENTITY)==identity for v in (reply,provenance))
              and call["runId"]==st["run"], f"第 {n} 组请求/回复/供应商身份不一致")
        check(wrapped.get("failureCode") == provenance.get("failureCode"), f"第 {n} 组的失败码不同")
        db.execute("INSERT INTO receipt_ids VALUES(?)", (call["attemptId"],))
        inc(st["receiptOutcomes"], provenance["outcome"])
        if provenance.get("requestId"):
            st["identifiedProviderReceipts"] = st.get("identifiedProviderReceipts", 0) + 1
        usage=reply.get("usage")
        if usage:
            for key in ("inputTokens","outputTokens"):
                if usage.get(key) is not None:
                    inc(st["providerUsage"],key,usage[key])
            inc(st["providerUsage"], "costStatus/"+str(usage.get("costStatus")))
        else:
            inc(st["providerUsage"], "withoutProviderUsage")
        st["receiptIndex"]=n


def sample_once(path, cache, observe_frame=None):
    path=path.resolve(strict=True)
    source=path/"world.journal"
    check(not cache.resolve().is_relative_to(path),"采样缓存须在样本目录之外")
    status=complete_json(path/"status.json")
    if not source.exists():
        return {"statusFile":status,"journal":"尚未出现","sample":str(path)}
    cache.parent.mkdir(parents=True,exist_ok=True)
    with closing(sqlite3.connect(cache)) as db, db, source.open("rb") as stream:
        db.execute("CREATE TABLE IF NOT EXISTS actions(id TEXT PRIMARY KEY,person TEXT NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS receipt_ids(id TEXT PRIMARY KEY)")
        db.execute("CREATE TABLE IF NOT EXISTS checkpoint(id INTEGER PRIMARY KEY,data TEXT NOT NULL)")
        db.execute("BEGIN IMMEDIATE")
        saved=db.execute("SELECT data FROM checkpoint WHERE id=1").fetchone()
        st=json.loads(saved[0]) if saved else initial()
        if saved and "readIds" not in st and any(p.get("completedReads",0) for p in st["people"].values()):
            raise ValueError("旧缓存没有首读证据，请指定新的 --cache 文件以重新核对")
        stat=os.fstat(stream.fileno())
        identity=[str(source),stat.st_dev,stat.st_ino]
        check(st.get("file",identity)==identity and stat.st_size>=st["offset"],"日志被更换或截短；请用新缓存重新核对")
        st["file"]=identity
        if st["offset"]:
            stream.seek(st["offset"]-32)
            check(journal_read(stream,32).hex()==st["anchor"],"上次确认的帧尾已改变")
        before=st["position"]
        while item:=next_frame(stream,stat.st_size):
            frame,anchor=item
            ingest(frame,st,db)
            if observe_frame is not None:
                observe_frame(frame,st,db)
            st.update(offset=stream.tell(),anchor=anchor)
        if st["position"]:
            receipts(path,st,db)
        db.execute("INSERT OR REPLACE INTO checkpoint VALUES(1,?)", (json.dumps(st,ensure_ascii=False,default=str),))
    events={}
    for d in st["days"].values():
        for k,v in d["events"].items(): inc(events,k,v)
    return dict(sample=str(path),runId=st.get("run"),policies=st.get("policies"),position=st["position"],
                timeMillis=st["time"],stateDigest=st["digest"],newFrames=st["position"]-before,
                verifiedBytes=st["offset"],pendingTailBytesAtOpen=stat.st_size-st["offset"],
                statusFile=status,completeSegmentReceiptPrefix=st["receiptIndex"],providerOutcomes=st["receiptOutcomes"],
                reportedUsage=st["providerUsage"],nativeDispatches=events.get("DECISION_DISPATCH_V1",0),
                naturalPersons=len(st.get("natural",[])),personsDispatched=sum(x.get("dispatches",0)>0 for x in st["people"].values()),
                completedReads=events.get(READ,0),peopleWithCompletedReads=sum(x.get("completedReads",0)>0 for x in st["people"].values()),
                orderReports=events.get(ORDER,0),trades=events.get(TRADE,0),listedIssuers=st.get("issuers",{}),
                openingCash=st.get("openingCash"),cashAccounts=len(st.get("accounts",{})),byPerson=st["people"],
                tradesBySymbol=st["symbols"],decisionOutcomes=st["audits"],byDay=st["days"])


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sample",type=Path,help="含 world.journal 与 status.json 的单个样本段目录")
    parser.add_argument("--cache",type=Path,help="样本以外的 SQLite 检查点，可重复使用")
    args=parser.parse_args()
    cache=args.cache or Path(__file__).resolve().parent/"monitor-cache"/("reads-v2-"+hashlib.sha256(str(args.sample.resolve()).encode()).hexdigest()+".sqlite")
    path=args.sample.resolve(strict=True)
    cfg=complete_json(path/"config.json")
    status=complete_json(path/"status.json")
    if cfg and complete_json(Path(str(path)+".exit.json")) is None:
        reason="ACTIVE_WRITER_USING_PUBLISHED_FILES"
    else:
        reason=None
        try:
            result=sample_once(path,cache)
        except PermissionError as failure:
            if (not cfg or not failure.filename or Path(failure.filename).resolve()!=(path/"world.journal").resolve()
                    or complete_json(Path(str(path)+".exit.json")) is not None):raise
            reason="NATIVE_JOURNAL_READ_DENIED"
    if reason:
        import study
        import published_progress
        result=published_progress.inspect(path,cfg["sourceCommit"],cfg,study.RUNS[cfg["mode"]],status,
                                          cache.with_suffix(".published-v2.sqlite"),reason)
    print(json.dumps(result,ensure_ascii=False,indent=2))
