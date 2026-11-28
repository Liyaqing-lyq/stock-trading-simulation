"""由已封原件生成十公司三样本报告；本模块没有调度或供应商客户端。"""
from collections import Counter, defaultdict
from decimal import Decimal
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import struct
import sys
import tempfile
from types import SimpleNamespace
import uuid

import study

DAY = study.DAY
HERE = Path(__file__).resolve().parent
ANALYSIS = HERE.parent / "20261127-natural-study-analysis"


def helper(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def readers(frozen):
    sys.path.insert(0,str(frozen/"scripts"))
    import audit_ten_company_run as day
    import audit_ten_company_settlements as settlements
    import live_sample
    for module in (day, settlements):
        study.need(Path(module.__file__).resolve().parent == frozen/"scripts", "审计器不属于指定冻结提交")
    companies=helper("company_cut_report",ANALYSIS/"study-company-summary/report.py")
    media=helper("media_cut_report",ANALYSIS/"study-media-summary/summarize.py")
    media.source_accounting=helper("media_sources",study.REPO/"scripts/audit_ten_company_media.py").source_accounting
    market=helper("market_cut_report",study.REPO/"scripts/audit_ten_company_market.py")
    study.need(companies.checked_opening is live_sample.checked_opening is day.checked_opening,"混用了不同提交的开局核验")
    owner=helper("owner_performance_cut",study.REPO/"scripts/report_owner_performance.py")
    cognition=helper("person_history_cut",HERE.parent/"20261127-natural-person-audit/longitudinal.py")
    return SimpleNamespace(day=day,settlements=settlements,companies=companies,media=media,cognition=cognition,
                           market=market,people=live_sample,owner=owner)


def write(path, obj):
    with path.open("w",encoding="utf8") as handle:
        json.dump(obj,handle,ensure_ascii=False,default=str,separators=(",",":"))
        handle.write("\n")


def prefix(api, source, dest, limit):
    """只复制确认时间 <= limit 的实际完整帧；下一帧只参与验证边界。"""
    now=0
    last=None
    with source.open("rb") as raw, dest.open("xb") as out:
        for frame in api.checked_frames(source,hashlib.sha256()):
            pos=frame["position"]
            delta, = [api.decode(e["payload"]) for e in frame["events"] if e["type"]=="STATE_DELTA_V1"]
            patch=delta["patch"]
            prev=now
            if pos==1: now=patch["value"]["timeMillis"]
            elif "timeMillis" in patch["changes"]: now=api.patch_field(now,patch["changes"]["timeMillis"])
            study.need(now>=prev,"历史截面时钟倒退")
            if now>limit:
                study.need(last is not None,"目标早于开局")
                return dict(**last,firstExcludedPosition=pos,firstExcludedTimeMillis=now)
            header=raw.read(8)
            length,inv=struct.unpack(">ii",header)
            study.need(~length==inv,"原始长度头错误")
            length=(length & 0x7fffffff)+32
            out.write(header)
            while length:
                piece=raw.read(min(length,1024*1024))
                study.need(piece,"历史截面原件不全")
                out.write(piece)
                length-=len(piece)
            last=dict(position=pos,timeMillis=now,bytes=raw.tell())
    study.need(last is not None and last["timeMillis"]==limit,"原件尚未到达要求的截止")
    return dict(**last,firstExcludedPosition=None,firstExcludedTimeMillis=None)


def native_media(api, path, day):
    result={"admin":{"runId":day["runId"],"position":day["lastPosition"],"timeMillis":day["lastTimeMillis"]}}
    marks={}
    state,_,_,sources,sha,_=api.journal(path.parent,result,marks)
    study.need(sha==day["journalSHA256"],"媒体截面的原件不同")
    # 仅当时已原生派发的本人模型输入；尚在途的下一条回复不得纳入截面。
    rows,feeds={},defaultdict(list)
    decisions=state["decisions"]
    for d in decisions["state"]["decisions"]:
        for attempt in d["attempts"]:
            aid=attempt["attemptId"]
            if aid not in decisions["dispatches"]: continue
            visible=json.loads(d["inputText"])
            study.need(visible["person"]==d["personPartyId"],"派发与本人可见输入不同")
            rows[aid]={"actionId":d["actionId"],"person":visible["person"]}
            for item in visible.get("groups",{}).get("feed",[]):
                feeds[(item["docId"],item["version"])].append((aid,visible["person"]))
    study.need(set(rows)==set(decisions["dispatches"]),"截止时有确认派发无法归人")
    return dict(**result["admin"],journalSha256=sha,
                basis="Cutoff-native dispatches and available bodies only",
                communication=api.communication(state,rows,feeds,sources,marks))


def render(api,frozen,path,cutdir,scratch,origin,cutoff,*,totals=None,segments=None):
    cutdir.mkdir()
    day=api.day.audit(path,cutoff//DAY if cutoff%DAY==0 else None)
    settle=api.settlements.audit(path)
    companies=api.companies.report(SimpleNamespace(journal=path,frozen=frozen,cadence="both"))
    market=api.market.report(path,0,settle)
    cache_root=scratch.parent if scratch.resolve()==path.parent.resolve() else scratch
    with tempfile.TemporaryDirectory(dir=cache_root) as cachedir:
        persons=api.people.sample_once(path.parent,Path(cachedir)/"people.sqlite")
    sha=day["journalSHA256"]
    run=day["runId"]
    p,t=day["lastPosition"],day["lastTimeMillis"]
    owner=api.owner.export(path,frozen,scratch)
    accounts=owner["accounts"]
    closing=day["daysWithConfirmedFrames"][t//DAY]
    study.need((owner["journalSha256"],owner["runId"],owner["startPosition"],owner["endPosition"],owner["endTimeMillis"])
               ==(sha,run,day["opening"]["position"],p,t),"财产报表与确认截面不符")
    for field,observed in (("openingCash",day["opening"]["cashByAccount"]),("cutoffCash",closing["cashByAccount"])):
        study.need({row["accountId"]:Decimal(row[field]) for row in accounts}
                   =={account:Decimal(str(amount)) for account,amount in observed.items()},"账户现金与独立日表不符")
    study.need(Decimal(owner["openingTotalCash"])==Decimal(str(day["openingCash"]))
               and Decimal(owner["cutoffTotalCash"])==Decimal(str(closing["cashTotal"])),"总现金与独立日表不符")
    write(cutdir/"owner-performance.json",owner)
    study.need(settle["journalSHA256"]==companies["journalSha256"]==market["journalSHA256"]==sha
               and run==companies["runId"]==persons["runId"]==market["runId"]==settle["runId"]
               and p==persons["position"]==settle["lastPosition"]==market["intervalEnd"]["position"]
               and t==persons["timeMillis"]==companies["cutoffTimeMillis"]==settle["lastTimeMillis"]<=cutoff,
               "经济、市场、人物和公司报表的时点不同")
    history=api.cognition.analyse(path.parent,p,path,segments)
    study.need(history["prefixSha256"]==sha and history["runId"]==run and history["timeMillis"]==t,
               "个人判断核验的来源或截点不同")
    if segments is not None:
        study.need(not history["nativeDispatchesWithoutLocalCallFile"],"本期确认派发无法与已封存的外发档匹配")
    write(cutdir/"personal-history.json",history)
    companies["source"]=str(origin)
    companies["sourcePrefixBytes"]=path.stat().st_size
    for key in ("sample","statusFile","reportedUsage","providerOutcomes","completeSegmentReceiptPrefix"):
        persons.pop(key,None)
    persons.update(source=str(origin),sourcePrefixBytes=path.stat().st_size,journalSha256=sha)
    for name,obj in (("daily",day),("settlements",settle),("companies",companies),("market",market),("persons",persons)):
        write(cutdir/(name+".json"),obj)
    if totals is None:
        media=native_media(api.media,path,day)
    else:
        study.need(totals["runId"]==run and totals["position"]==p and totals["journalSha256"]==sha,"错用媒体终态")
        media={k:totals[k] for k in ("runId","position","timeMillis","journalSha256","communication")}
        media["basis"]="Sealed physical inputs and cutoff-native ledger"
    write(cutdir/"media.json",media)
    return dict(runId=run,position=p,timeMillis=t,requestedCutoffMillis=cutoff,
                sourceJournal=str(origin),journalSha256=sha,sourcePrefixBytes=path.stat().st_size,
                openingStateDigest=day["opening"]["stateDigest"],openingPosition=day["opening"]["position"],
                cashAccounts=len(accounts),registeredOwners=len(owner["owners"]),performanceStatuses=owner["performanceStatuses"],
                tradeCount=settle["tradeCount"],companyDayRows=len(companies["rows"]["day"]),
                companyQuarterRows=len(companies["rows"]["quarter"]),nativeDispatches=persons["nativeDispatches"])


def sample(api,frozen,source,folder,name,scratch):
    journal=source/"world.journal"
    seal=study.read(Path(str(source)+".strict-reopen.json"))
    cfg=study.read(source/"config.json")
    days=study.DAYS[name]
    study.need(cfg["targetMillis"]==days*DAY==int(seal["timeMillis"])
               and cfg["sourceCommit"]==folder.name==seal["verifiedAgainstCommit"],"未到本样本预注册终点")
    dest=folder/"reports"/name
    identity=dict(sourceCommit=folder.name,sample=name,actualRunId=seal["runId"],terminalSegment=str(source),
                  terminalJournalSha256=seal["journalSha256"],targetDays=days)
    if (dest/"manifest.json").is_file():
        old=study.read(dest/"manifest.json")
        verified=(old["identity"]==identity and old["schema"]==1 and old.get("reporterDigest")==reporter_digest()
                  and all((dest/k).is_file() and study.digest(dest/k)==v for k,v in old["fileDigests"].items()))
        if verified:return old
    dest.parent.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(dir=dest.parent,prefix=".report-stage-") as temp:
        stage=Path(temp)/"tables"
        stage.mkdir()
        archives=list(api.media.envelopes(source))
        totals=api.media.summarize(source,archives)
        raw_segments=[seg for seg,_ in archives]
        segments=[]
        aggregated_models=Counter()
        failures=Counter()
        for seg,result in archives:
            sealrow=study.read(Path(str(seg)+".strict-reopen.json"))
            prov=result["providerProvenance"]
            aggregated_models.update(prov["reportedModelCounts"])
            failures.update(prov["transport"]["failuresByCode"])
            segments.append(dict(path=str(seg),runId=sealrow["runId"],position=sealrow["position"],
                timeMillis=sealrow["timeMillis"],journalSha256=sealrow["journalSha256"],httpCalls=result["httpCalls"],
                modelRoutingSha256=study.digest(seg/"scene-routes.json"),systemPromptSha256=study.digest(seg/"system-prompt.txt"),
                stop=result["probeStop"],hostReportedFailures=int(result["autonomy"]["failures"]),
                provider=prov,costStatus=result["costStatus"]))
        write(stage/"physical-attribution.json",totals)
        cuts={}
        with tempfile.TemporaryDirectory(dir=scratch) as tmp:
            tmp=Path(tmp)
            if days==180:
                h=tmp/"history"
                h.mkdir()
                info=prefix(api.day,journal,h/"world.journal",90*DAY)
                cuts["D90"]=render(api,frozen,h/"world.journal",stage/"D90",h,journal,90*DAY,segments=raw_segments)
                study.need((cuts["D90"]["sourcePrefixBytes"],cuts["D90"]["timeMillis"],cuts["D90"]["position"])
                           ==(info["bytes"],info["timeMillis"],info["position"]),"历史截面核验错误")
                cuts["D90"]["firstExcludedPosition"]=info["firstExcludedPosition"]
                cuts["D90"]["firstExcludedTimeMillis"]=info["firstExcludedTimeMillis"]
            key=f"D{days}"
            cuts[key]=render(api,frozen,journal,stage/key,tmp,journal,days*DAY,totals=totals,segments=raw_segments)
        study.need(cuts[key]["journalSha256"]==identity["terminalJournalSha256"] and
                   int(seal["position"])==cuts[key]["position"] and cuts[key]["timeMillis"]==days*DAY,
                   "终态报告未复现严格封卷")
        technical={r["attemptId"] for r in totals["attempts"] if r["outcome"]=="TECHNICAL_FAILED" or r["providerFailure"]}
        eligibility="ROBUSTNESS_ONLY" if technical or any(failures.values()) or any(seg["hostReportedFailures"] for seg in segments) else "ELIGIBLE_PRIMARY_BEHAVIOR_COMPARISON"
        usage={}
        for key in ("inputTokens","outputTokens"):
            amounts=[a["usage"][key] for a in totals["attempts"] if a["usage"] and a["usage"].get(key) is not None]
            usage[key]={"reportedSum":sum(amounts),"attemptsReporting":len(amounts),
                        "attemptsMissing":len(totals["attempts"])-len(amounts)}
        report=dict(schema=1,identity=identity,reporterDigest=reporter_digest(),sourceSegments=segments,
                    reportedUsage=usage,
                    openingConfiguration=study.read(folder/(name+".json")),cutoffs=cuts,
                    technicalFailureAttemptIds=sorted(technical),targetTransportFailures=failures,
                    physicalRequests=totals["physicalRequestCount"],reportedModelCounts=aggregated_models,
                    eligibility=eligibility,
                    fileDigests={str(p.relative_to(stage)):study.digest(p) for p in sorted(stage.rglob("*.json"))})
        write(stage/"manifest.json",report)
        if dest.exists():
            # 重建派生包时原包仍可复查；实验原卷从不写入。
            backup=dest.with_name("."+dest.name+"-previous-"+uuid.uuid4().hex)
            dest.rename(backup)
            try:stage.rename(dest)
            except OSError:
                backup.rename(dest)
                raise
        else:stage.rename(dest)
        return report


def reporter_digest():
    inputs=json.dumps(study.reporting_sources(),sort_keys=True,separators=(",",":")).encode()
    return hashlib.sha256(inputs).hexdigest()


def finish(frozen,folder,sha,completed,all_samples,incomplete):
    if incomplete:
        # 与最终汇总分开；提前终止或没有开局时不虚构原生标识/封卷结果。
        index=folder/"reports"
        index.mkdir(exist_ok=True)
        for name,state in incomplete.items():
            snapshot=dict(sourceCommit=sha,sample=name,registeredConfig=str(folder/(name+".json")),
                          targetDays=study.DAYS[name],eligibility="NOT_ELIGIBLE_NO_VERIFIED_TARGET",**state)
            with tempfile.NamedTemporaryFile(dir=index,delete=False) as tmp: tmpname=Path(tmp.name)
            write(tmpname,snapshot)
            tmpname.replace(index/(name+"-last-incomplete.json"))
        if not completed:raise ValueError("无到期封卷可生成正式报告；已保留各样本实际状态")

    scratch=HERE.parent/"20261128-v15-components/temporary/study-reports"
    scratch.mkdir(parents=True,exist_ok=True)
    for key in ("TMP","TEMP","TMPDIR"): os.environ[key]=str(scratch)
    os.environ["JAVA_TOOL_OPTIONS"]=(os.environ.get("JAVA_TOOL_OPTIONS","")+" -Djava.io.tmpdir="+str(scratch)).strip()
    tempfile.tempdir=str(scratch)
    api=readers(frozen)
    reports={name:sample(api,frozen,out,folder,name,scratch) for name,out in completed.items()}
    if all_samples:
        study.need(set(reports)==set(study.DAYS),"三份正式终点尚未全部取得；独立样本报告已保留")
        study.need(len({r["identity"]["terminalSegment"] for r in reports.values()})==len(reports)
                   and all(r["openingConfiguration"]["seed"] is None for r in reports.values()),"样本初始配置或原件路径不独立")
        summary=dict(sourceCommit=sha,samples={name:dict(**r["identity"],sourceSegments=r["sourceSegments"],
                     openingConfiguration=r["openingConfiguration"],cutoffs=r["cutoffs"],
                     eligibility=r["eligibility"],technicalFailureAttemptIds=r["technicalFailureAttemptIds"],
                     physicalRequests=r["physicalRequests"],reportedUsage=r["reportedUsage"],manifest=str(folder/"reports"/name/"manifest.json"))
                     for name,r in reports.items()})
        dest=folder/"reports/study-summary.json"
        with tempfile.NamedTemporaryFile(dir=dest.parent,delete=False) as tmp: tmpname=Path(tmp.name)
        write(tmpname,summary)
        tmpname.replace(dest)
        print("STUDY_REPORT",dest,flush=True)
