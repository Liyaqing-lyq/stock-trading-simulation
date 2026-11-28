"""十公司自然样本的离线准备、启动及校准封卷复核。在 worker 仓根用 python -B 运行。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

STUDY = Path(__file__).resolve().parent
CYCLE = STUDY.parent
REPO = CYCLE.parent.parent
LAUNCHER = CYCLE / "run-live-v24.cjs"
REOPEN = STUDY / "StrictStudyReopen.java"
PREREG = "docs/experiments/2026-11-27-十公司自然运行评价.md"
MODE = "TEN_COMPANY_AUTONOMY"
RUN_ID = "web-ten-company-autonomy-v2"
STUDY_MODE = "TEN_COMPANY_STUDY"
GOVERNANCE_MODE = "TEN_COMPANY_GOVERNANCE_STUDY"
EDITORIAL_MODE = "TEN_COMPANY_EDITORIAL_STUDY"
PERSONAL_MODE = "TEN_COMPANY_PERSONAL_STUDY"
INTERVIEW_MODE = "TEN_COMPANY_INTERVIEW_STUDY"
MEETING_MODE = "TEN_COMPANY_MEETING_STUDY"
DIGITAL_MODE = "TEN_COMPANY_DIGITAL_WIND_DOWN_STUDY"
FEEDBACK_MODE = "TEN_COMPANY_FEEDBACK_STUDY"
NEWS_MODE = "TEN_COMPANY_PERSONAL_NEWS_STUDY"
ACTIVE_INTERVIEW_MODE = "TEN_COMPANY_INTERVIEW_AUTONOMY_STUDY"
ISSUER_MODE = "TEN_COMPANY_ISSUER_DECISIONS_STUDY"
MEDIA_MODE = "TEN_COMPANY_MEDIA_WITHDRAWAL_STUDY"
CURRENT_MODE = "TEN_COMPANY_LOAN_LOSS_STUDY"
BELIEF_MODE = "TEN_COMPANY_PERSONAL_BELIEF_STUDY"
INTERVIEW_TIME_MODE = "TEN_COMPANY_INTERVIEW_TIME_STUDY"
DIVIDEND_AMENDMENT_MODE = "TEN_COMPANY_DIVIDEND_AMENDMENT_STUDY"
AGGREGATOR_MODE = "TEN_COMPANY_AGGREGATOR_EVIDENCE_STUDY"
ROLE_ARGUMENTS_MODE = "TEN_COMPANY_ROLE_ARGUMENTS_STUDY"
EXACT_DECIMAL_MODE = "TEN_COMPANY_EXACT_DECIMAL_STUDY"
EDITORIAL_SOURCE_MODE = "TEN_COMPANY_EDITORIAL_SOURCE_STUDY"
CRISIS_ARGUMENTS_MODE = "TEN_COMPANY_CRISIS_ARGUMENTS_STUDY"
ONSITE_CLOCK_MODE = "TEN_COMPANY_ONSITE_DECISION_CLOCK_STUDY"
GOVERNANCE_POLICIES = {"document-reading-clock":"body-pacing-300-v1",
                    "editorial-template-guide":"office-source-map-keys-v1",
                    "issuer-period-metrics":"operating-evidence-v1"}
EDITORIAL_POLICIES = {**GOVERNANCE_POLICIES,"editorial-draft-visibility":"submitted-document-v1"}
PERSONAL_POLICIES = {**EDITORIAL_POLICIES,"crisis-office-objectives":"active-appointments-v1",
                    "personal-note-contract":"explicit-note-reference-v1",
                    "decision-envelope-guide":"typed-review-fields-v1"}
INTERVIEW_POLICIES = {**PERSONAL_POLICIES,"interview-action-feedback":"bound-coordination-v1",
                    "social-action-feedback":"bound-message-v1","periodic-action-feedback":"bound-period-result-v1"}
MEETING_POLICIES = {**INTERVIEW_POLICIES,"periodic-meeting-view":"readable-period-material-v1"}
DIGITAL_POLICIES = {**MEETING_POLICIES,"digital-cessation":"board-wind-down-v1"}
FEEDBACK_POLICIES = {**DIGITAL_POLICIES,"editorial-review-feedback":"private-revision-reading-v1","interview-window-basis":"execution-clock-v1","web-person-takeover":"marked-branch-v1"}
NEWS_POLICIES = {**FEEDBACK_POLICIES,"personal-news-ranking":"visible-interest-v1","news-exploration-slot":"4",
                    "market-session-wake":"role-exposure-choice-v1","media-subscriptions":"consented-interview-delivery-v1",
                    "cross-border-editing":"dual-clock-editing-v1","pharma-licensing":"approved-use-contract-v1","hydro-inflow-path":"finite-1080d-v1"}
ACTIVE_INTERVIEW_POLICIES = {**NEWS_POLICIES,"interview-autonomy":"active-session-turn-v1"}
ISSUER_POLICIES = {**ACTIVE_INTERVIEW_POLICIES,"issuer-decisions":"engineering-cash-dividend-v1",
                    "personal-market-orders":"immediate-unfilled-cancel-v1"}
MEDIA_POLICIES = {**ISSUER_POLICIES,"media-withdrawal":"published-version-notice-v1","media-work-calendar":"finite-business-shifts-v1",
                    "dividend-claims":"holder-and-issuer-followup-v1","personal-attention":"weekday-duty-windows-v1"}
CURRENT_POLICIES = {**MEDIA_POLICIES,"bank-loan-loss":"manager-impair-writeoff-v1","material-information-trading":"issuer-disclosure-window-v1","customer-personal-tools":"personal-communication-v1"}
BELIEF_POLICIES = {**CURRENT_POLICIES,"personal-belief-contract":"visible-orders-evidence-v1"}
INTERVIEW_TIME_POLICIES = {**BELIEF_POLICIES,"interview-time-contract":"effective-window-reading-floor-v1"}
DIVIDEND_AMENDMENT_POLICIES = {**INTERVIEW_TIME_POLICIES,"issuer-dividend-amendments":"board-correction-before-record-v1"}
ONSITE_CLOCK_POLICIES = {**DIVIDEND_AMENDMENT_POLICIES,"onsite-decision-clock":"observed-action-completion-v1",
                         "personal-discovery-catalog":"bounded-catalog-search-pages-v1",
                         "personal-participant-history":"bounded-personal-history-v1"}
CRISIS_ARGUMENTS_POLICIES = {**ONSITE_CLOCK_POLICIES,"crisis-action-arguments":"operation-specific-line-identity-v1",
                            "board-research-arguments":"action-and-evidence-reference-v1"}
AGGREGATOR_POLICIES = {**CRISIS_ARGUMENTS_POLICIES, "aggregator-evidence":"read-published-stories-only-v1"}
ROLE_ARGUMENTS_POLICIES = {**AGGREGATOR_POLICIES, "role-tool-arguments":"conditional-fields-and-readable-errors-v1",
                           "public-post-review-wake":"defer-catalog-count-with-post-v1"}
EXACT_DECIMAL_POLICIES = {**ROLE_ARGUMENTS_POLICIES,"decision-argument-decimals":"exact-json-decimal-v1"}
EDITORIAL_SOURCE_POLICIES = {**EXACT_DECIMAL_POLICIES,"editorial-source-accounting":"registered-origin-groups-v1"}
POLICIES = {EDITORIAL_SOURCE_MODE:EDITORIAL_SOURCE_POLICIES,EXACT_DECIMAL_MODE:EXACT_DECIMAL_POLICIES,ROLE_ARGUMENTS_MODE:ROLE_ARGUMENTS_POLICIES,AGGREGATOR_MODE:AGGREGATOR_POLICIES,CRISIS_ARGUMENTS_MODE:CRISIS_ARGUMENTS_POLICIES,ONSITE_CLOCK_MODE:ONSITE_CLOCK_POLICIES,DIVIDEND_AMENDMENT_MODE:DIVIDEND_AMENDMENT_POLICIES,INTERVIEW_TIME_MODE:INTERVIEW_TIME_POLICIES,BELIEF_MODE:BELIEF_POLICIES,MEDIA_MODE:MEDIA_POLICIES,FEEDBACK_MODE:FEEDBACK_POLICIES,GOVERNANCE_MODE:GOVERNANCE_POLICIES,EDITORIAL_MODE:EDITORIAL_POLICIES,PERSONAL_MODE:PERSONAL_POLICIES,INTERVIEW_MODE:INTERVIEW_POLICIES,MEETING_MODE:MEETING_POLICIES,DIGITAL_MODE:DIGITAL_POLICIES,NEWS_MODE:NEWS_POLICIES,ACTIVE_INTERVIEW_MODE:ACTIVE_INTERVIEW_POLICIES,ISSUER_MODE:ISSUER_POLICIES,CURRENT_MODE:CURRENT_POLICIES}
RUNS = {EDITORIAL_SOURCE_MODE:"web-ten-company-autonomy-v24",EXACT_DECIMAL_MODE:"web-ten-company-autonomy-v23",ROLE_ARGUMENTS_MODE:"web-ten-company-autonomy-v22",AGGREGATOR_MODE:"web-ten-company-autonomy-v21",MODE:RUN_ID, STUDY_MODE:"web-ten-company-autonomy-v3",GOVERNANCE_MODE:"web-ten-company-autonomy-v4",
        EDITORIAL_MODE:"web-ten-company-autonomy-v5",PERSONAL_MODE:"web-ten-company-autonomy-v6",INTERVIEW_MODE:"web-ten-company-autonomy-v7",MEETING_MODE:"web-ten-company-autonomy-v8",DIGITAL_MODE:"web-ten-company-autonomy-v9",FEEDBACK_MODE:"web-ten-company-autonomy-v10",NEWS_MODE:"web-ten-company-autonomy-v11",ACTIVE_INTERVIEW_MODE:"web-ten-company-autonomy-v12",ISSUER_MODE:"web-ten-company-autonomy-v13",MEDIA_MODE:"web-ten-company-autonomy-v14",CURRENT_MODE:"web-ten-company-autonomy-v15",BELIEF_MODE:"web-ten-company-autonomy-v16",INTERVIEW_TIME_MODE:"web-ten-company-autonomy-v17",DIVIDEND_AMENDMENT_MODE:"web-ten-company-autonomy-v18",ONSITE_CLOCK_MODE:"web-ten-company-autonomy-v19",CRISIS_ARGUMENTS_MODE:"web-ten-company-autonomy-v20"}

def auditor(mode):
    need(mode==EDITORIAL_SOURCE_MODE,"此独立驱动只启动或严格重开二十四版")
    return STUDY/"v24-auditor"/REOPEN.name

DAY = 86_400_000
DAYS = {"calibration": 90, "evaluation-1": 180, "evaluation-2": 180}
FROZEN_TEST = "edu.cufe.auction.web.TargetTransportProbeTest"
BASIC_TEST = "edu.cufe.auction.web.LongHorizonBudgetTest"
LEGACY_FAIL_RULE = "target-transient-eight-v1"
MEASURED_FAIL_RULE = "target-attributed-failures-eight-v2"

def need(ok, message):
    if not ok:
        raise ValueError(message)

def digest(file):
    with file.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def read(file):
    return json.loads(file.read_bytes())

def save(file, data):
    with file.open("x", encoding="utf8", newline="\n") as stream:
        json.dump(data, stream, indent=2, ensure_ascii=False)
        stream.write("\n")

def git(*args):
    return subprocess.check_output(["git","-C",str(REPO),*args])

def resolve_sha(value):
    need(re.fullmatch(r"[a-fA-F0-9]{7,40}",value), "需要 Git 提交 SHA")
    return git("rev-parse","--verify",value+"^{commit}").decode("ascii").strip()

def source_matches(frozen, archive, sha):
    entries = git("ls-tree","-rz",sha,"--","pom.xml","src","scripts/audit_ten_company_run.py",
                  "scripts/audit_ten_company_settlements.py",PREREG)
    paths = []
    for entry in entries.split(b"\0"):
        if not entry:
            continue
        meta, raw = entry.split(b"\t",1)
        _, kind, oid = meta.split()
        name = raw.decode("utf8")
        need(kind == b"blob","冻结树存在无支持的非文件")
        file = frozen / name
        content = file.read_bytes()
        need(content == archive.read(name),"冻结源码与原始归档不符 "+name)
        # git archive 在本机可应用 text/eol 属性。确认未改动 Git blob。
        def blob(data):
            return hashlib.sha1(b"blob "+str(len(data)).encode()+b"\0"+data).hexdigest().encode()
        need(blob(content) == oid or blob(content.replace(b"\r\n", b"\n")) == oid, "Git 内容不符 "+name)
        paths.append(name)
    return paths

def fingerprint_outputs(folders):
    h=hashlib.sha256()
    for folder in folders:
        for file in sorted(p for p in folder.rglob("*") if p.is_file()):
            h.update((folder.name+"/"+file.relative_to(folder).as_posix()).encode()+b"\0")
            h.update(bytes.fromhex(digest(file)))
    return h.hexdigest()

def frozen_target_rule(frozen):
    source=frozen/"src/test/java/edu/cufe/auction/web/TargetTransportPolicy.java"
    if not source.is_file(): return None
    match=re.search(r'^\s*static final String VERSION\s*=\s*"([^"]+)";',source.read_text("utf8"),re.MULTILINE)
    return match.group(1) if match else None

def frozen_mode(frozen):
    manifest=(frozen/"src/main/java/edu/cufe/auction/world/RunManifest.java").read_text("utf8")
    if re.search(r'EDITORIAL_SOURCE_RUN_ID\s*=\s*"web-ten-company-autonomy-v24"',manifest): return EDITORIAL_SOURCE_MODE
    if re.search(r'EXACT_DECIMAL_RUN_ID\s*=\s*"web-ten-company-autonomy-v23"',manifest): return EXACT_DECIMAL_MODE
    if re.search(r'ROLE_ARGUMENTS_RUN_ID\s*=\s*"web-ten-company-autonomy-v22"',manifest): return ROLE_ARGUMENTS_MODE
    if re.search(r'AGGREGATOR_EVIDENCE_RUN_ID\s*=\s*"web-ten-company-autonomy-v21"',manifest): return AGGREGATOR_MODE
    if re.search(r'CRISIS_ARGUMENTS_STUDY_RUN_ID\s*=\s*"web-ten-company-autonomy-v20"',manifest): return CRISIS_ARGUMENTS_MODE
    if re.search(r'ONSITE_DECISION_CLOCK_RUN_ID\s*=\s*"web-ten-company-autonomy-v19"',manifest): return ONSITE_CLOCK_MODE
    if re.search(r'DIVIDEND_AMENDMENT_RUN_ID\s*=\s*"web-ten-company-autonomy-v18"',manifest): return DIVIDEND_AMENDMENT_MODE
    if re.search(r'INTERVIEW_TIME_RUN_ID\s*=\s*"web-ten-company-autonomy-v17"',manifest): return INTERVIEW_TIME_MODE
    if re.search(r'PERSONAL_BELIEF_RUN_ID\s*=\s*"web-ten-company-autonomy-v16"',manifest): return BELIEF_MODE
    if re.search(r'BANK_LOAN_LOSS_RUN_ID\s*=\s*"web-ten-company-autonomy-v15"',manifest): return CURRENT_MODE
    if re.search(r'MEDIA_WITHDRAWAL_RUN_ID\s*=\s*"web-ten-company-autonomy-v14"',manifest): return MEDIA_MODE
    if re.search(r'ISSUER_DECISIONS_RUN_ID\s*=\s*"web-ten-company-autonomy-v13"',manifest): return ISSUER_MODE
    if re.search(r'INTERVIEW_AUTONOMY_RUN_ID\s*=\s*"web-ten-company-autonomy-v12"',manifest): return ACTIVE_INTERVIEW_MODE
    if re.search(r'PERSONAL_NEWS_RUN_ID\s*=\s*"web-ten-company-autonomy-v11"',manifest): return NEWS_MODE
    if re.search(r'EDITORIAL_FEEDBACK_RUN_ID\s*=\s*"web-ten-company-autonomy-v10"',manifest): return FEEDBACK_MODE
    if re.search(r'DIGITAL_WIND_DOWN_RUN_ID\s*=\s*"web-ten-company-autonomy-v9"',manifest): return DIGITAL_MODE
    if re.search(r'MEETING_TEN_COMPANY_RUN_ID\s*=\s*"web-ten-company-autonomy-v8"',manifest): return MEETING_MODE
    if re.search(r'INTERVIEW_TEN_COMPANY_RUN_ID\s*=\s*"web-ten-company-autonomy-v7"',manifest): return INTERVIEW_MODE
    if re.search(r'PERSONAL_TEN_COMPANY_RUN_ID\s*=\s*"web-ten-company-autonomy-v6"',manifest): return PERSONAL_MODE
    if re.search(r'READABLE_DRAFT_TEN_COMPANY_RUN_ID\s*=\s*"web-ten-company-autonomy-v5"',manifest): return EDITORIAL_MODE
    if re.search(r'GOVERNANCE_TEN_COMPANY_RUN_ID\s*=\s*"web-ten-company-autonomy-v4"',manifest): return GOVERNANCE_MODE
    return STUDY_MODE if re.search(r'STUDY_TEN_COMPANY_AUTONOMY_RUN_ID\s*=\s*"web-ten-company-autonomy-v3"',manifest) else MODE

def reporting_sources():
    # Record the actual cutoff readers separately from the simulation commit.
    analysis = CYCLE / "20261127-natural-study-analysis"
    paths = (
        STUDY / "final_reports.py", STUDY / "live_sample.py", STUDY / "progress.py", STUDY / "published_progress.py",
        analysis / "company_summary.py", analysis / "study-company-summary/report.py", analysis / "study-media-summary/summarize.py",
        CYCLE / "20261127-natural-person-audit/longitudinal.py",
        REPO / "scripts/audit_ten_company_market.py", REPO / "scripts/audit_ten_company_media.py",
        REPO / "scripts/report_owner_performance.py", REPO / "scripts/OwnerPerformanceReport.java",
    )
    return {path.relative_to(REPO).as_posix(): digest(path) for path in paths}


def freeze(sha):
    root = CYCLE/("20261127-verification-"+sha[:7])
    f = root/"frozen"
    record = read(root/"exit.json")
    need(record.get("source")==sha and all(type(record.get(x)) is int and record[x]==0 for x in ("verify","javadoc")),
         "验证或 JavaDoc 尚未以该 SHA 成功封卷")
    with zipfile.ZipFile(root/"source.zip") as archive:
        need(archive.comment.decode("ascii")==sha,"源码归档的提交不相符")
        source = source_matches(f,archive,sha)
    reports = f/"target/surefire-reports"
    xml = reports/("TEST-"+FROZEN_TEST+".xml")
    has_target = xml.is_file()
    report = ET.parse(xml if has_target else reports/("TEST-"+BASIC_TEST+".xml")).getroot()
    need(int(report.get("tests",0))>0 and all(report.get(k)=="0" for k in ("failures","errors","skipped")),
         "冻结 Surefire 报告缺少通过证据")
    props = {p.get("name"):p.get("value") for p in report.find("properties")}
    cp = props["java.class.path"]
    need(cp==props["surefire.test.class.path"],"两个 Surefire 类路径不一致")
    raw = cp.split(os.pathsep)
    if raw[-1]=="": raw.pop()  # Surefire 的末尾分号不能把宿主当前目录加入正式运行。
    need(all(x and Path(x).is_absolute() for x in raw),"类路径含空项或相对目录")
    paths = [Path(x).resolve(strict=True) for x in raw]
    expected = [(f/"target"/name).resolve() for name in ("test-classes","classes")]
    m2=(REPO/"logs/m2").resolve(strict=True)
    need(paths[:2]==expected and len(paths)>2 and len(set(paths))==len(paths),"类路径不属于该验证构建或重复")
    need(Path(props["basedir"]).resolve()==f.resolve() and Path(props["java.home"]).resolve()==(REPO/"logs/jdk").resolve()
         and Path(props["localRepository"]).resolve()==m2,"Surefire 根目录、JDK 或依赖库不同")
    need(all(p.is_file() and p.suffix==".jar" and p.is_relative_to(m2) for p in paths[2:]),"依赖不在冻结 Maven 仓库")
    manifest = (f/"src/main/java/edu/cufe/auction/world/RunManifest.java").read_text("utf8")
    target_class = expected[0]/"edu/cufe/auction/web/TargetTransportPolicy.class"
    blockers = []
    mode=frozen_mode(f)
    if not re.search(r'TEN_COMPANY_AUTONOMY_RUN_ID\s*=\s*"web-ten-company-autonomy-v2"',manifest): blockers.append("非正式十公司 v2")
    rule=frozen_target_rule(f)
    if not has_target or rule not in (LEGACY_FAIL_RULE,MEASURED_FAIL_RULE) or not target_class.is_file() or rule.encode() not in target_class.read_bytes(): blockers.append("没有目标运行传输策略及验收")
    if mode != MODE and (rule!=MEASURED_FAIL_RULE or mode.encode() not in (expected[0]/"edu/cufe/auction/web/AutonomousLifecycleProbe.class").read_bytes()):
        blockers.append("缺少所选版本正式开局或已归属失败取证政策")
    if mode in POLICIES:
        compiled=(expected[1]/"edu/cufe/auction/world/RunManifest.class").read_bytes()
        if any(token.encode() not in compiled for pair in POLICIES[mode].items() for token in pair):
            blockers.append("冻结清单缺少该实验版本的开局政策")
    if PREREG not in source: blockers.append("提交中未冻结预注册文件")
    elif mode == EDITORIAL_SOURCE_MODE:
        preregistration=(f/PREREG).read_text("utf8")
        additions=EDITORIAL_SOURCE_POLICIES.items()-CRISIS_ARGUMENTS_POLICIES.items()
        if RUNS[mode] not in preregistration or any(term not in preregistration for pair in additions for term in pair):
            blockers.append("预注册缺少二十四版身份、汇编、条件参数、十进制或稿源识别政策")
    else:
        blockers.append("本独立驱动只接受二十四版正式自然样本")
    cp_text=os.pathsep.join(str(p) for p in paths)
    facts={"source":sha,"preregistration":PREREG,"preregistrationSha256":digest(f/PREREG) if PREREG in source else None,
           "sourceFilesChecked":len(source),"classpathSha256":hashlib.sha256(cp_text.encode()).hexdigest(),
           "classesSha256":fingerprint_outputs(expected),"dependencies":{str(p):digest(p) for p in paths[2:]},
           "launcherSha256":digest(LAUNCHER),"strictReopenSourceSha256":digest(auditor(EDITORIAL_SOURCE_MODE)),"targets":DAYS,"blockers":blockers}
    facts["studyDriverSha256"] = digest(Path(__file__))
    facts["reportingSources"] = reporting_sources()
    if mode != MODE:
        facts.update(targetFailurePolicy=rule,mode=mode,runId=RUNS[mode])
    if mode in POLICIES: facts["requiredOpeningPolicies"]=POLICIES[mode]
    return f,facts,cp_text

def config(sha, sample, seed=None, mode=MODE):
    return {"mode":mode,"maxHttpCalls":0,"wallMinutes":0,"targetMillis":DAYS[sample]*DAY,
            "stepMillis":3_600_000,"seed":str(seed) if seed else None,"sourceCommit":sha}

def prepare(folder, sha, facts, cp_text):
    need(not facts["blockers"],"；".join(facts["blockers"]))
    need(facts.get("mode")==EDITORIAL_SOURCE_MODE,"新的正式校准或评价必须使用冻结 v24")
    need(facts.get("requiredOpeningPolicies")==EDITORIAL_SOURCE_POLICIES,"准备清单必须声明正式 v24 开局政策")
    need(not folder.exists(),"准备目录已存在，拒绝覆盖")
    with tempfile.TemporaryDirectory(dir=STUDY) as temp:
        stage=Path(temp)/"prepared"
        stage.mkdir()
        (stage/"source-commit.txt").write_text(sha+"\n",encoding="ascii")
        (stage/"classpath.txt").write_text(cp_text,encoding="utf8")
        for name in DAYS: save(stage/(name+".json"),config(sha,name,mode=facts.get("mode",MODE)))
        shutil.copyfile(CYCLE/("20261127-verification-"+sha[:7])/"frozen"/PREREG,stage/"preregistration.md")
        save(stage/"prepared.json",facts)
        stage.rename(folder)

def verify_envelope(out, cfg, previous, rule=LEGACY_FAIL_RULE):
    suffix=lambda s: Path(str(out)+s)
    rc=read(suffix(".exit.json"))
    need(type(rc.get("code")) is int and rc["code"]==0 and rc.get("signal") is None,"样本进程未正常结束")
    receipt=read(suffix(".process.json"))
    need(receipt.get("sourceCommit")==cfg["sourceCommit"] and receipt.get("mode")==cfg["mode"] and receipt.get("maxHttpCalls")==0,
         "进程回执与正式配置不一致")
    r=read(out/"result.json")
    need(read(out/"config.json")==cfg==r.get("config"),"样本配置、人员范围、来源或截止不符")
    a,l,p = r["autonomy"],r["live"],r["providerProvenance"]
    need(a["running"] is False and not l["fault"] and l["phase"]=="IDLE" and not l["pendingPersons"]
         and l["physicalInFlight"]==l["completionSlots"]==0, "样本仍有未结的角色或外发")
    admin=r["admin"]
    need(admin["position"]==l["position"] and admin["timeMillis"]==l["timeMillis"]
         and int(a["position"])<=int(l["position"]) and int(a["timeMillis"])<=int(l["timeMillis"])
         and admin["runId"]==l["runId"]==RUNS[cfg["mode"]],
         "终点身份或时点不一致")
    for b in [l["runBudget"], *(row["budget"] for row in l["personBudgets"])]:
        need(all(int(b[k])==0 for k in ("reservedCalls","reservedInputTokens","reservedOutputTokens")), "外发预算仍有预留")
    n=r["httpCalls"]
    need(type(n) is int and n>=0 and p["evidenceComplete"] is True and p["completedCalls"]==p["recordedCalls"]==n
         and p["failureCode"] is None and read(out/"provider-provenance-policy.json")["transportFailurePolicy"]==p["transport"]["policy"]==rule and p["transport"]["thresholdReached"] is False,
         "供应商证据或故障状态未封卷")
    seal=read(out/"measured-length-evidence.json")
    need(seal["policy"]=="measured-length-local-v1" and seal["httpCalls"]==n
         and seal["parentSealSha256"]==(digest(previous/"measured-length-evidence.json") if previous else None),
         "样本封卷父子链不符")
    h=hashlib.sha256()
    def add(name):
        h.update(name.encode()+b"\0"+bytes.fromhex(digest(out/name)))
    for name in ("config.json","result.json","system-prompt.txt","scene-routes.json","provider-provenance-policy.json",
                 "world.journal","local-input-events.jsonl"): add(name)
    for file in out.iterdir():
        m=re.fullmatch(r"(call|reply|provider)-(\d+)\.json",file.name)
        if m:
            i=int(m[2])
            need(1<=i<=n and m[2]==str(i),"卷宗有未计数的外发证据 "+file.name)
    for i in range(1,n+1):
        for prefix in ("call","reply","provider"): add(f"{prefix}-{i}.json")
    need(h.hexdigest()==seal["archiveSha256"],"实际卷宗与封存摘要不同")
    if previous:
        with (previous/"world.journal").open("rb") as parent, (out/"world.journal").open("rb") as child:
            while chunk:=parent.read(1024*1024):
                need(child.read(len(chunk))==chunk,"续行日志未保留母卷原生前缀")
    return r

def check_opening_manifest(frame, mode):
    manifests=[event for event in frame["events"] if event["type"]=="RUN_MANIFEST_V1"]
    need(len(manifests)==1,"首帧没有唯一的开局清单")
    manifest=json.loads(manifests[0]["payload"])["manifest"]
    need(frame["position"]==1 and manifest["runId"]==frame["runId"]==RUNS[mode],"首帧清单身份与模式不同")
    need(mode in (CRISIS_ARGUMENTS_MODE,AGGREGATOR_MODE,ROLE_ARGUMENTS_MODE,EXACT_DECIMAL_MODE,EDITORIAL_SOURCE_MODE),"不是二十至二十四版原始样本")
    need(manifests[0]["payload"]==frame["commandContent"] and manifest["dataVersions"].get("branch-profile-run")==RUNS[mode],
         "正式开局命令、清单或分支身份不同")
    expected=POLICIES[mode]
    for key,value in expected.items():
        need(manifest["dataVersions"].get(key)==value,"正式开局首帧政策不同 "+key)
    need(not (EDITORIAL_SOURCE_POLICIES.keys()-expected.keys()).intersection(manifest["dataVersions"]),
         "旧版声明了后续版本的政策")
    return manifest

def confirm_clock(frozen, out, r):
    run_id=RUNS[r["config"]["mode"]]
    # 直接用同一提交的审计器读取原生帧，派生结果 JSON 的时间不足以证明到达。
    api=runpy.run_path(str(frozen/"scripts/audit_ten_company_run.py"))
    position=0
    now=None
    chain=hashlib.sha256(b"null").hexdigest()
    for frame in api["checked_frames"](out/"world.journal",hashlib.sha256()):
        position=frame["position"]
        events=[e for e in frame["events"] if e["type"]=="STATE_DELTA_V1"]
        need(frame["runId"]==run_id and len(events)==1,"样本包含其它运行或缺少差量")
        delta=api["decode"](events[0]["payload"])
        need(delta["runId"]==run_id and delta["version"]=="delta-v1" and delta["position"]==position
             and delta["basePosition"]==position-1 and delta["baseDigest"]==chain,"原生日志摘要不连续")
        chain=delta["resultDigest"]
        patch=delta["patch"]
        if position==1:
            check_opening_manifest(frame,r["config"]["mode"])
            opening=patch["value"]
            now=opening["timeMillis"]
            registry=api["decode"](next(e["payload"] for e in frame["events"] if e["type"]=="OPENING_REGISTRY_V1"))
            people=api["opening_people"](registry,opening["accounts"],opening["securities"]["lots"])
            need(len({s["symbol"] for s in registry["securities"]})==10
                 and len({s["issuerOrganizationId"] for s in registry["securities"]})==10,"开局股票和发行人不符")
            need(now==0 and len(people["naturalPersons"])==61 and people["systemPublishers"]==["research-system"],
                 "开局时点或人物清单不属于这份预注册")
        elif "timeMillis" in patch["changes"]:
            nxt=api["patch_field"](now,patch["changes"]["timeMillis"])
            need(nxt>=now,"校准时钟倒退")
            now=nxt
    need(position==int(r["live"]["position"]) and now==int(r["live"]["timeMillis"]),
         "真实日志终点与样本报告不一致")

def strict_reopen(out, cp, mode=MODE):
    java_auditor=auditor(mode)
    source=out/"world.journal"
    before=digest(source)
    with tempfile.TemporaryDirectory(dir=STUDY) as temp:
        journal=Path(temp)/"world.journal"
        shutil.copyfile(source,journal)
        need(digest(journal)==before,"严格重开副本与封卷不符")
        result=subprocess.run([str(REPO/"logs/jdk/bin/java.exe"),"-Djava.io.tmpdir="+temp,"-Xmx2048m","-Dfile.encoding=UTF-8","-Dstdout.encoding=UTF-8","-Dstderr.encoding=UTF-8",
                               "-cp",cp,str(java_auditor),str(journal),str(out)],cwd=temp,capture_output=True)
        after=digest(journal)
    need(digest(source)==before==after,"严格重开改变了输入或副本")
    need(result.returncode==0,"冻结 Java 严格重开失败\n"+result.stderr.decode("utf8","replace")[-3500:])
    proof=json.loads(result.stdout)
    need(proof["runId"]==RUNS[mode],"重开回执缺少正式档身份")
    if mode in POLICIES: need(proof.get("openingPolicies")==POLICIES[mode],"严开回执与原生开局政策不同")
    proof.update(journalSha256=before,copyAfterReopenSha256=after,strictReopenSourceSha256=digest(java_auditor),
                 classpathSha256=hashlib.sha256(cp.encode()).hexdigest())
    return proof

def inspect_segments(frozen, folder, sha, name, cp, record=False):
    previous=None
    index=1
    while True:
        out=folder/f"{name}-s{index:03d}"
        related=[out,*(Path(str(out)+ext) for ext in (".process.log",".process.json",".exit.json"))]
        if not any(p.exists() for p in related):
            return "NEW",out,previous
        need(all(p.exists() for p in related),"样本在运行或原进程未完整封卷，拒绝再启动 "+str(out))
        mode=frozen_mode(frozen)
        cfg=config(sha,name,previous,mode)
        r=verify_envelope(out,cfg,previous,frozen_target_rule(frozen))
        confirm_clock(frozen,out,r)
        proof=strict_reopen(out,cp,mode)
        proof["verifiedAgainstCommit"]=sha
        receipt=Path(str(out)+".strict-reopen.json")
        if receipt.exists(): need(read(receipt)==proof,"已封的 Java 严开证据不同")
        elif record: save(receipt,proof)
        state=r["probeStop"]
        if state=="TARGET_REACHED":
            need(r["autonomy"]["reason"]==state and int(r["live"]["timeMillis"])==cfg["targetMillis"],
                 "自然样本未达其预注册截止")
            return "DONE",out,previous
        need(state=="SAFE_CHECKPOINT" and r["autonomy"]["reason"] in ("STOPPED","NOT_STARTED")
             and int(r["live"]["timeMillis"])<cfg["targetMillis"],"人工停止或故障，拒绝自动续跑 "+state)
        previous=out
        index+=1

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action",choices=("dry-run","prepare","run","check","report"))
    parser.add_argument("sha")
    parser.add_argument("sample",nargs="?",choices=(*DAYS,"all"))
    args=parser.parse_args()
    sha=resolve_sha(args.sha)
    frozen,facts,cp=freeze(sha)
    folder=STUDY/sha
    if args.action=="dry-run":
        print(json.dumps({"dryRun":True,"externalCalls":0,"verifiedFrozenPrerequisites":not facts["blockers"],"eligible":not facts["blockers"] and facts.get("mode")==EDITORIAL_SOURCE_MODE,"studyMode":facts.get("mode",MODE),"source":sha,
                          "sourceFilesChecked":facts["sourceFilesChecked"],"dependencyCount":len(facts["dependencies"]),
                          "preregistrationSha256":facts["preregistrationSha256"],"blockers":facts["blockers"]},ensure_ascii=False))
        return
    need(not facts["blockers"],"；".join(facts["blockers"]))
    mode=facts.get("mode",MODE)
    if args.action in ("prepare","run"):
        need(mode==EDITORIAL_SOURCE_MODE,"旧版普通十公司存档保留复核，新的自然样本使用正式 v24")
    if args.action=="prepare":
        prepare(folder,sha,facts,cp)
        print("PREPARED",folder)
        return
    need(args.sample,"run/check/report 需要样本名称或 all")
    need(read(folder/"prepared.json")==facts and (folder/"classpath.txt").read_text("utf8")==cp
         and (folder/"source-commit.txt").read_text("ascii").strip()==sha
         and digest(folder/"preregistration.md")==facts["preregistrationSha256"],"已准备工件或冻结运行时内容有改动")
    calibrated=False
    completed={}
    incomplete={}
    for name in (DAYS if args.sample=="all" else [args.sample]):
        if args.action=="run" and name!="calibration" and not calibrated:
            need(inspect_segments(frozen,folder,sha,"calibration",cp,True)[0]=="DONE","校准尚未达到第90日，评价不可启动")
            calibrated=True
        need(read(folder/(name+".json"))==config(sha,name,mode=mode),"预注册的独立开局配置不一致")
        if args.action=="report":
            # 只处理已封卷；有在写进程的样本只登记公布状态，不接触其 world.journal。
            segments=sorted(folder.glob(name+"-s[0-9][0-9][0-9]"))
            latest=segments[-1] if segments else None
            if latest and not Path(str(latest)+".exit.json").is_file():
                incomplete[name]={"status":"UNSEALED","segment":str(latest)}
                continue
            try:
                state,out,seed=inspect_segments(frozen,folder,sha,name,cp,True)
            except (OSError,ValueError,KeyError,subprocess.SubprocessError) as failure:
                result=read(latest/"result.json") if latest and (latest/"result.json").is_file() else {}
                incomplete[name]={"status":"UNVERIFIED_OR_EARLY_TERMINATION",
                    "segment":str(latest) if latest else None,"reportedStop":result.get("probeStop"),
                    "reportedRunId":result.get("live",{}).get("runId"),"reportedTimeMillis":result.get("live",{}).get("timeMillis"),
                    "verificationError":str(failure)}
                continue
        else:
            state,out,seed=inspect_segments(frozen,folder,sha,name,cp,args.action=="run")
        if args.action=="run" and state=="NEW":
            params=folder/(name+".json")
            if seed:
                params=folder/(out.name+"-resume.json")
                if params.exists(): need(read(params)==config(sha,name,seed,mode),"已留存的续行配置发生变动")
                else: save(params,config(sha,name,seed,mode))
            subprocess.run(["node",str(LAUNCHER),str(folder),str(params),str(out)],cwd=REPO,check=True)
            state,out,seed=inspect_segments(frozen,folder,sha,name,cp,args.action=="run")
            need(state=="DONE","已安全停点；显式再次 run 可续该样本")
        print(name,state,out)
        if state=="DONE":
            completed[name]=out
            if name=="calibration": calibrated=True
        elif args.action=="report":
            incomplete[name]={"status":"SAFE_CHECKPOINT" if seed else "NOT_STARTED",
                              "segment":str(seed) if seed else None}
            if seed:
                proof=read(Path(str(seed)+".strict-reopen.json"))
                incomplete[name].update(actualRunId=proof["runId"],confirmedPosition=proof["position"],
                                        confirmedTimeMillis=proof["timeMillis"],journalSha256=proof["journalSha256"])
            print(name,incomplete[name]["status"],flush=True)
    if args.action in ("run","report") and (completed or args.action=="report"):
        # 报告只读已确认卷宗；即使失败，单独 report 可重算，不经过 Node 或业务入口。
        from final_reports import finish
        finish(frozen,folder,sha,completed,args.sample=="all",incomplete)

if __name__=="__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, subprocess.SubprocessError, zipfile.BadZipFile) as failure:
        sys.exit(str(failure))
