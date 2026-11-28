"""在可读的已确认普通十公司前缀中联查个人实发输入、实际笔记、行动与认知分录。"""
from argparse import ArgumentParser
from collections import Counter, defaultdict
from itertools import islice
from pathlib import Path
import hashlib
import json
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "scripts"))
from audit_ten_company_run import checked_frames, checked_opening, patch_field, checked_reread


def check(condition, reason):
    if not condition:
        raise ValueError(reason)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def load(path):
    return json.loads(path.read_bytes())


def pick(row, names):
    return {k:row[k] for k in names.split() if k in row}


def identity(obj):
    return tuple(obj[k] for k in ("runId", "actionId", "attemptId", "attemptNo", "recoveryGeneration"))


def formal(book, policy):
    beliefs = {v["beliefId"]:v for v in book["beliefs"]}
    evaluations = {v["evaluationId"]:v for v in book["evaluations"]}
    superseded = {v["supersedesEvaluationId"] for v in evaluations.values() if v.get("supersedesEvaluationId")}
    check(superseded <= evaluations.keys(), "核验所取代的结论不存在")
    effective = {}
    scores = defaultdict(list)
    for eid, ev in evaluations.items():
        b = beliefs[ev["beliefId"]]
        check(b["personPartyId"] == ev["personPartyId"], "预测与评估人物不一致")
        if eid in superseded:
            continue
        check(b["beliefId"] not in effective, "同一判断有多个有效核验")
        effective[b["beliefId"]] = eid
        if b.get("adoptedClaimId"):
            check((ev.get("adoptedClaimId"), ev.get("sourcePartyId")) == (b["adoptedClaimId"], b["sourcePartyId"]), "采纳的来源不一致")
            scores[(b["personPartyId"],b["sourcePartyId"])].append((eid,ev["verdict"]))
    levels = {(r["personPartyId"],r["kind"]):r["toLevel"] for r in book["habitRevisions"]}
    def rating(es):
        check(all(v in ("HIT","MISS") for _,v in es), "未知有效评估")
        return max(0,min(1000,500 + policy["trustStep"]*sum(1 if v=="HIT" else -1 for _,v in es)))
    return beliefs, evaluations, effective, {k:(rating(es),[eid for eid,_ in es]) for k,es in scores.items()}, levels


def analyse(segment, limit, journal, segments=None):
    digest = hashlib.sha256()
    previous = sha(b"null")
    state = {}
    people = []
    native = defaultdict(lambda: defaultdict(list))
    dispatch = {}; audit = {}; observations = {}; reads = {}; read_ids = {}; reports = defaultdict(list); commands = {}
    prev_formal = ({},{},{},{},{})
    known_habit = set()
    run = policy = None
    position = now = 0
    frames = checked_frames(journal, digest)
    for frame in (islice(frames, limit) if limit is not None else frames):
        position = frame["position"]
        events = frame["events"]
        deltas = [json.loads(e["payload"]) for e in events if e["type"]=="STATE_DELTA_V1"]
        check(len(deltas)==1, f"p{position} 缺权威差量")
        delta = deltas[0]
        if position == 1:
            opening = checked_opening(frame)
            run = frame["runId"]
            policy = opening["information"]["beliefPolicy"]
            people = [p["partyId"] for p in opening["registry"]["parties"] if p["kind"]=="PERSON" and p["partyId"]!="research-system"]
        check((frame["runId"],delta["runId"],delta["version"],delta["position"],delta["basePosition"],delta["baseDigest"])
              == (run,run,"delta-v1",position,position-1,previous), f"p{position} 链身份不一致")
        previous = delta["resultDigest"]
        patch = delta["patch"]
        if position==1:
            check(patch["kind"]=="replace","缺少首帧")
            state = {k:patch["value"][k] for k in ("timeMillis","beliefs","decisions")}
        else:
            check(patch["kind"]=="object","不支持未核对的根替换")
            for k in state.keys() & patch["changes"].keys():
                state[k] = patch_field(state[k],patch["changes"][k])
        old_time, now = now,state["timeMillis"]
        check(now>=old_time,"时钟倒退")
        if position==1 or "beliefs" in patch.get("changes",{}):
            book = state["beliefs"]["beliefs"]
            current = formal(book,policy)
            if position!=1:
                beliefs, evaluations, _, scores, levels = current
                for name,index in (("formedBeliefs",0),("evaluations",1)):
                    for key in current[index].keys()-prev_formal[index].keys():
                        row = current[index][key]
                        native[row["personPartyId"]][name].append({"position":position,"time":now,"entry":row})
                for key in scores.keys()|prev_formal[3].keys():
                    before = prev_formal[3].get(key,(500,[]))
                    after = scores.get(key,(500,[]))
                    if before[0]!=after[0]:
                        native[key[0]]["sourceScoreChanges"].append(dict(position=position,time=now,sourcePartyId=key[1],
                            fromPermille=before[0],toPermille=after[0],previousEvaluations=before[1],effectiveEvaluations=after[1]))
                for rev in book["habitRevisions"]:
                    if rev["revisionId"] not in known_habit:
                        check(set(rev["supportBeliefIds"]) <= beliefs.keys(),"习惯支持判断不存在")
                        native[rev["personPartyId"]]["habitRevisions"].append(dict(position=position,time=now,entry=rev))
            # patch_field 会就地更新上一帧的内部对象；只保留独立不可变比较数据。
            prev_formal = current
            known_habit = {r["revisionId"] for r in book["habitRevisions"]}
        for e in events:
            kind=e["type"]
            if kind=="STATE_DELTA_V1": continue
            if kind=="DECISION_DISPATCH_V1":
                r=json.loads(e["payload"]);check(r["attemptId"] not in dispatch,"重复派出尝试")
                dispatch[r["attemptId"]]=(position,r)
            elif kind=="DECISION_AUDIT_V1":
                r=json.loads(e["payload"])
                if r.get("kind")=="REPLY":
                    check(r["attemptId"] not in audit,"重复回复登记")
                    audit[r["attemptId"]]=r
            elif kind=="DECISION_ACTION_SCHEDULED_V1":
                work=json.loads(e["payload"])
                if work.get("clientCommandId") and work.get("commandId"):commands[work["clientCommandId"]]=work["commandId"]
            elif kind=="TASK_COMPLETED_V1" and frame["commandId"].startswith("task:"):
                tid=frame["commandId"][5:]
                work=json.loads(e["payload"])
                if not isinstance(work,dict):continue
                keys={tid,work.get("commandId"),work.get("clientCommandId")}-{None}
                row=dict(event=kind,framePosition=position,frameTime=now,taskId=tid,result=frame["result"],
                         **pick(work,"personPartyId kind clientCommandId commandId"))
                for key in keys:reports[key].append(row)
                for module,table in (("media","reports"),("interviews","coordinations")):
                    change=patch.get("changes",{}).get(module,{}).get("changes",{}).get(table,{}).get("changes",{}).get(tid)
                    if not change or change["kind"]!="replace":continue
                    r=change["value"]
                    check(r["position"]==position and r["taskId"]==tid and r["timeMillis"]==now and r["reason"]==frame["result"],"任务与业务状态回执不符")
                    proof=dict(event=module+"."+table,framePosition=position,frameTime=now,**pick(r,"taskId kind personPartyId accepted reason requestId storyId"))
                    for key in keys:reports[key].append(proof)
            elif kind=="INFORMATION_OBSERVATION_V1":
                r=json.loads(e["payload"]);repeat=checked_reread(frame,e["payload"],r,now,read_ids)
                rr=[json.loads(x["payload"]) for x in events if x["type"]=="INFORMATION_REPORT_V1"]
                check(len(rr)==1 and rr[0]["accepted"] and rr[0]["kind"]=="INFORMATION_COMPLETE_READ_V1"
                      and rr[0]["readerPartyId"]==r["personPartyId"] and rr[0]["position"]==position
                      and rr[0]["timeMillis"]==now,"缺少实读完成凭证")
                read=dict(position=position,time=now,readId=rr[0]["subjectId"],repeat=repeat,
                          **pick(r,"docId version personPartyId title completedAt"),commandId=rr[0]["commandId"])
                reads.setdefault((r["personPartyId"],r["docId"],r["version"],r["completedAt"]),read)
                reports[rr[0]["commandId"]].append({"event":kind,**read})
            elif "REPORT" in kind or kind in ("BANK_ACTION_V1","BANK_ACTION_BEGIN_V1"):
                r=json.loads(e["payload"])
                if isinstance(r,dict) and "accepted" in r:
                    row=dict(event=kind,framePosition=position,frameTime=now,**pick(r,"taskId commandId clientCommandId personPartyId readerPartyId kind accepted reason subjectId"))
                    for key in set(r.get(k) for k in ("clientCommandId","commandId","taskId"))-{None}:
                        reports[key].append(row)
    frames.close()
    check(position and (limit is None or position==limit),"所指定的确认位置不存在")
    decisions={r["actionId"]:r for r in state["decisions"]["state"]["decisions"]}
    refs=state["decisions"]["actionRefs"]
    attempts={at["attemptId"]:(d,at) for d in decisions.values() for at in d["attempts"]}
    partial = []
    rows = []
    files = (p for part in (segments or [segment]) for p in sorted(
        (entry for entry in part.glob("call-*.json") if entry.stem[5:].isdigit()), key=lambda entry:int(entry.stem[5:])))
    externals=set(); unreadable_files=[]
    for sequence,p in enumerate(files,1):
        local=int(p.stem[5:])
        n=sequence if segments is not None else local
        if n>len(dispatch):break
        try:c=load(p)
        except (PermissionError,FileNotFoundError,json.JSONDecodeError):
            if limit is None:raise
            unreadable_files.append(p.name);continue
        aid=c["attemptId"]
        if aid not in dispatch: continue
        check(aid not in externals,"重复实发编号");externals.add(aid)
        start,dis=dispatch[aid];d,at=attempts[aid];act=d["actionId"]
        check((act,at["attemptId"],at["attemptNo"],at["recoveryGeneration"],run) ==
              (c["actionId"],aid,c["attemptNo"],c["recoveryGeneration"],c["runId"]),f"#{n} 身份不匹配")
        raw=c["inputText"];checksum=sha(raw.encode())
        envelope=dict(domain="decision-input-v1",toolContractVersion=d["toolContractVersion"],allowedTools=d["allowedTools"],inputText=raw)
        native_checksum=sha(json.dumps(envelope,ensure_ascii=False,separators=(",",":")).encode())
        check(raw==d["inputText"] and c["allowedTools"]==d["allowedTools"] and native_checksum==dis["inputDigest"]==d["inputDigest"]==at["inputDigest"],f"#{n} 本人输入与正式档不一致")
        obs=json.loads(raw);who=obs["person"];check(who==d["personPartyId"],"人物错配")
        reply_path=p.parent/f"reply-{local}.json"; provider_path=p.parent/f"provider-{local}.json"
        body=None;provider=None;received=False
        if provider_path.exists() and aid in audit:
            provider=load(provider_path);check(identity(provider)==identity(c),f"#{n} 提供方身份不同")
        if reply_path.exists() and at.get("replyReceived") and aid in audit:
            reply=load(reply_path).get("reply")
            if reply:
                check(identity(reply)==identity(c) and reply["body"]==at["replyBody"] and
                      sha(reply["body"].encode())==audit[aid]["bodySha256"],f"#{n} 原回复正文/摘要不符")
                try: body=json.loads(reply["body"])
                except (ValueError,TypeError): pass
                received=True
        if provider is None or not received: partial.append(n)
        obj=body if isinstance(body,dict) else {}
        recorded=aid in audit and audit[aid]["outcome"]=="RECORDED" and d["status"]=="RECORDED" and at["outcome"] in ("INTENT","WAIT")
        expected = obj.get("privateNote")
        note_raw = "privateNote" in obj
        evidence=[]
        if recorded and isinstance(expected,dict):
            citable={(v["docId"],v["version"]) for v in obs["engineering"]["researchNotes"]["citableReadings"]}
            known={(x["docId"],x["version"]):x for x in obs["profile"]["readExperiences"]}
            for ev in expected["evidence"]:
                key=(ev["docId"],ev["version"])
                check(key in citable and key in known,f"#{n} 笔记援引无已读来源")
                exp=known[key];proof=reads.get((who,*key,exp["completedAt"]))
                check(proof is not None and proof["position"]<=d["cutoffPosition"],f"#{n} 未匹配原生阅读凭据")
                evidence.append({k:proof[k] for k in ("readId","docId","version","title","completedAt","position")})
        current=obs["engineering"]["researchNotes"].get("current")
        used=obj.get("basedOnNote")
        if used and recorded:
            check(current and current["actionId"]==used,f"#{n} 引用与本人收到的当前笔记不符")
        ref=refs.get(act,{}) if recorded else {}
        keys={act,ref.get("taskId"),commands.get(act)}
        if ref.get("taskId"): keys.add(ref["taskId"]+":finish")
        witnesses=[]
        for k in keys-{None}:
            for r in reports.get(k,[]):
                if r not in witnesses:witnesses.append(r)
        witnesses.sort(key=lambda x:(x.get("framePosition",x.get("position",0)),x["event"]))
        item=dict(call=n,segment=p.parent.name,localCall=local,person=who,time=obs["time"],inputCutoff=d["cutoffPosition"],dispatchPosition=start,
                  actionId=act,attemptId=aid,inputSha256=checksum,rawReplyVerified=received,externalRequestId=provider.get("requestId") if provider else None,
                  verifiedExternalInput=bool(provider and provider.get("requestId") and provider.get("providerReportedModel")),
                  nativeInputDigest=native_checksum,nativeReplyPosition=audit[aid]["position"] if aid in audit else None,recorded=recorded,outcome=at.get("outcome"),
                  proposedTool=obj.get("tool"),nativeTool=(d.get("recordedTool") or ("role.wait" if at.get("outcome")=="WAIT" else None)) if recorded else None,
                  proposedArgs=obj.get("args"),privateNoteProposed=note_raw,proposedNote=expected if note_raw else None,
                  recordedNote=recorded and note_raw,readEvidence=evidence,basedOnNote=used if recorded else None,
                  receivedPriorNote=current,receivedCognition=obs.get("cognition"),nextPaidInputMatch=None,strictProjectionMatch=None,
                  laterPaidUses=[],executionWitnesses=witnesses)
        observations[n]=obs;rows.append(item)
    not_in_files=sorted(set(dispatch)-externals)
    # 允许带受控前缀的分项日志；前缀上的历史决定不可归到本段付费样本。
    final=None;strict=segment/"strict.json"
    if strict.exists():
        value=load(strict)
        if value.get("position")==position and value.get("time")==now:
            final=value.get("finalObservation")
            sealed=load(segment/"result.json")
            check(sealed.get("position")==position and sealed.get("time")==now and sealed.get("finalObservation")==final,"结束观察与严格重开不符")
    groups=defaultdict(list)
    for r in rows:groups[r["person"]].append(r)
    for who,seq in groups.items():
        by_id={r["actionId"]:r for r in seq}
        for i,r in enumerate(seq):
            later=[a for a in seq[i+1:] if a["verifiedExternalInput"]]
            def matches(observed):
                return observed is None if r["proposedNote"] is None else (
                    observed is not None and observed["actionId"]==r["actionId"] and observed["note"]==r["proposedNote"])
            if r["recordedNote"]:
                if later:r["nextPaidInputMatch"]=matches(later[0]["receivedPriorNote"])
                if final and final["person"]==who:
                    r["strictProjectionMatch"]=matches(final["engineering"]["researchNotes"].get("current"))
            if r["basedOnNote"] in by_id:
                by_id[r["basedOnNote"]]["laterPaidUses"].append(dict(call=r["call"],actionId=r["actionId"],tool=r["nativeTool"],outcome=r["outcome"]))
            observed=observations[r["call"]].get("cognition")
            if i==0:r["exposedChangesSinceLastPaidInput"]=None
            else:
                old=observations[seq[i-1]["call"]].get("cognition")
                r["exposedChangesSinceLastPaidInput"]=[k for k in ("beliefs","sourceReliability","habits") if old.get(k)!=observed.get(k)] if observed and old else None
    categories=("formedBeliefs","evaluations","sourceScoreChanges","habitRevisions")
    return dict(runId=run,position=position,timeMillis=now,prefixSha256=digest.hexdigest(),stateDigestAtCut=previous,
        scope="原生确认前缀。rawReplyVerified 须有原始回复、原生尝试正文和登记散列。strictProjectionMatch 是封存时可见的查询，nextPaidInputMatch 才表示下一次实际来件。字段为 null 表示无观测。",
        completeInputFiles=len(rows),partialRawAttempts=partial,unreadableAtCut=unreadable_files,nativeDispatchesWithoutLocalCallFile=not_in_files,
        formalChanges={k:sum(len(n[k]) for n in native.values()) for k in categories},
        people={p:dict(calls=[r["call"] for r in groups[p]],nativeFormal={k:native[p][k] for k in categories})
                for p in sorted(set(people)|set(groups)|set(native))},calls=rows)


if __name__=="__main__":
    ap=ArgumentParser(description=__doc__)
    ap.add_argument("segment",type=Path);ap.add_argument("output",type=Path)
    ap.add_argument("--position",type=int,help="只读取到此已确认帧，不读后帧")
    ap.add_argument("--journal",type=Path,help="可用严格核实过的同段只读副本")
    args=ap.parse_args()
    if args.position is not None and args.position<1: ap.error("确认位置须为正")
    try: result=analyse(args.segment,args.position,args.journal or args.segment/"world.journal")
    except PermissionError: raise SystemExit("原生日志当前受写锁保护；请使用已发布的只读确认前缀或封存后重查。")
    with args.output.open("x",encoding="utf-8") as out:json.dump(result,out,ensure_ascii=False,indent=2)
    print(result["runId"],result["position"],result["completeInputFiles"],result["formalChanges"])
