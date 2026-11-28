"""封卷的累计行为摘要。只读原始请求、提供方应答、严格重开收据及原生业务状态。"""
import argparse
from collections import Counter, defaultdict
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import re
import sys

HERE = Path(__file__).resolve().parent
AREA = HERE.parent.parent
sys.dont_write_bytecode = True
sys.path[:0] = [str(AREA / "20261127-natural-study"), str(HERE.parent)]
import study
from company_summary import apply, checked_frames, decode

# V24 正式报告负责装入与其报告摘要绑定的只读统计模块。
source_accounting = None


def want(ok, message):
    if not ok:
        raise ValueError(message)


def select(row, *fields):
    return {k: row[k] for k in fields if k in row}


def envelopes(folder):
    match = re.fullmatch(r"(calibration|evaluation-[12])-s(\d{3})", folder.name)
    want(match, "请给出具体的 calibration-sNNN 或 evaluation-N-sNNN 目录")
    previous = None
    sample, limit = match.groups()
    want(int(limit) > 0, "样本段号从 1 开始")
    for n in range(1, int(limit) + 1):
        path = folder.parent / f"{sample}-s{n:03}"
        cfg = study.read(path / "config.json")
        sha = folder.parent.name
        frozen = AREA / ("20261127-verification-" + sha[:7]) / "frozen"
        mode = study.frozen_mode(frozen)
        result = study.verify_envelope(path, study.config(sha, sample, previous, mode), previous,
                                       study.frozen_target_rule(frozen))
        strict = study.read(Path(str(path) + ".strict-reopen.json"))
        want(cfg["sourceCommit"] == strict["verifiedAgainstCommit"] == sha
             and strict["runId"] == result["admin"]["runId"]
             and int(strict["position"]) == int(result["admin"]["position"])
             and int(strict["timeMillis"]) == int(result["admin"]["timeMillis"])
             and strict["journalSha256"] == strict["copyAfterReopenSha256"] == study.digest(path / "world.journal"),
             "原版严格重开收据与档案不符")
        yield path, result
        previous = path


def media_version_sources(desk, story, prefix, receipt):
    if prefix == "public" and receipt["kind"] == "MEDIA_STORY_WITHDRAW_V1":
        key = story["publicDocId"], story["publicVersion"]
        lineage, = [line for line in desk["lineage"] if (line["docId"], line["version"]) == key]
        want(story["status"] == "WITHDRAWN" and len(lineage["roots"]) == 1, "撤稿回执缺正式公告来源")
        return dict(confirmedAt=receipt["position"], roots=deepcopy(lineage["roots"]),
                    sourceGroups=None, evidence=[], versionKind="WITHDRAWAL_NOTICE")
    return dict(confirmedAt=receipt["position"], **deepcopy(select(story, "roots", "sourceGroups", "evidence")))


def journal(folder, result, editorial_marks=None):
    state, audits, revision_sources, event_reports = {}, defaultdict(list), {}, []
    digest, parent = hashlib.sha256(), hashlib.sha256(b"null").hexdigest()
    # 输入与已读正文数量很大。命令全文副本和经济账与这些统计无关。
    excluded = {"commandContents", "commandOutcomes", "delivered", "usedTaskIds", "accounts",
                "securities", "lastPhase", "schemaVersion"}
    for frame in checked_frames(folder / "world.journal", digest):
        position = frame["position"]
        all_events = frame["events"]
        delta, = [decode(e["payload"]) for e in all_events if e["type"] == "STATE_DELTA_V1"]
        want(delta["version"] == "delta-v1" and delta["baseDigest"] == parent and
             delta["basePosition"] + 1 == position == delta["position"] and
             frame["runId"] == delta["runId"] == result["admin"]["runId"], "非同一封卷的连续差量")
        parent = delta["resultDigest"]
        patch = delta["patch"]
        if position == 1:
            state = {k: v for k, v in patch["value"].items() if k not in excluded}
            registry, = [decode(e["payload"]) for e in all_events if e["type"] == "OPENING_REGISTRY_V1"]
            if source_accounting is not None:
                manifest = decode(frame["commandContent"])["manifest"]
                state["reportMediaOpening"] = dict(policy=manifest["dataVersions"].get("editorial-source-accounting"),
                                                   issuers=[s["issuerOrganizationId"] for s in registry["securities"]])
            people = sorted(p["partyId"] for p in registry["parties"] if p["kind"] == "PERSON"
                            and p["partyId"] != "research-system")
        else:
            for key in patch.get("removed", []):
                state.pop(key, None)
            for key, change in patch["changes"].items():
                if key not in excluded:
                    state[key] = apply(state.get(key), change)
        for e in all_events:
            # 部分程序只为董事会议、期间等动作发出事件回执，不放在模块的 reports 表里。
            if e["type"] != "STATE_DELTA_V1" and '"accepted"' in e["payload"]:
                receipt = decode(e["payload"])
                if isinstance(receipt, dict) and isinstance(receipt.get("accepted"), bool) and ("taskId" in receipt or "commandId" in receipt):
                    want(int(receipt.get("position", position)) == position, "事件回执位置错误")
                    event_reports.append(dict(**select(receipt, "taskId", "commandId", "kind", "action", "accepted", "reason"),
                                              position=position, event=e["type"]))
            if e["type"] == "DECISION_AUDIT_V1":
                a = decode(e["payload"])
                audits[a["attemptId"]].append(select(a, "position", "kind", "outcome", "bodySha256"))
            elif e["type"] == "INFORMATION_OBSERVATION_V1" and editorial_marks is not None:
                obs = decode(e["payload"])
                editorial_marks[("read", obs["readId"])] = position
            elif e["type"] == "MEDIA_TASK_V1":
                t = decode(e["payload"])
                r = state["media"]["reports"].get(t["taskId"])
                if not r or not r["accepted"] or r["position"] != position:
                    continue
                for desk in state["media"]["states"]:
                    for story in desk["stories"]:
                        if story["storyId"] != r["storyId"]:
                            continue
                        if editorial_marks is not None:
                            for rev in desk["revisions"]:
                                if rev["storyId"] == story["storyId"] and rev["atMillis"] == r["timeMillis"]:
                                    key = ("revision", rev["storyId"], rev["revisionNo"], rev["status"], rev["atMillis"])
                                    editorial_marks.setdefault(key, position)
                        # 同名稿件后来可能补引/改稿，根和分组必须保留当时该版的值。
                        for prefix in ("draft", "public"):
                            key = (story[prefix + "DocId"], story[prefix + "Version"])
                            if key[1] > 0 and key not in revision_sources:
                                revision_sources[key] = media_version_sources(desk, story, prefix, r)
    want(state["position"] == int(result["admin"]["position"]) and state["timeMillis"] == int(result["admin"]["timeMillis"]),
         "前端终态与原生记录不一致")
    return state, people, audits, revision_sources, digest.hexdigest(), event_reports


def physical(segments, decisions, audits):
    native = {a["attemptId"]: (d, a) for d in decisions["state"]["decisions"] for a in d["attempts"]}
    rows, feeds = {}, defaultdict(list)
    for folder, result in segments:
        for n in range(1, result["httpCalls"] + 1):
            c = study.read(folder / f"call-{n}.json")
            reply = study.read(folder / f"reply-{n}.json")
            p = study.read(folder / f"provider-{n}.json")
            a = c["attemptId"]
            rr = reply["reply"]
            dispatch = decisions["dispatches"][a]
            want(a not in rows, "跨段重复物理请求 " + a)
            for identity in ("runId", "actionId", "attemptId", "attemptNo", "recoveryGeneration"):
                want(c[identity] == p[identity] == rr[identity], "请求身份不一致 " + a)
                if identity != "runId":
                    want(c[identity] == dispatch[identity], "外发与原生派发不符 " + a)
            d, attempt = native[a]
            me = json.loads(c["inputText"])
            want(c["actionId"] == d["actionId"] and me["person"] == d["personPartyId"]
                 and c["inputText"] == d["inputText"] and p["failureCode"] == reply["failureCode"], "本人的输入或回报不符 " + a)
            sha = hashlib.sha256(rr["body"].encode()).hexdigest()
            want(any(row.get("bodySha256") == sha for row in audits[a]), "正式回执无此回复摘要 " + a)
            for entry in me.get("groups", {}).get("feed", []):
                feeds[(entry["docId"], entry["version"])].append((a, me["person"]))
            rows[a] = dict(segment=folder.name, number=n, person=me["person"], actionId=c["actionId"], attemptId=a,
                           outcome=attempt["outcome"], recordedTool=d.get("recordedTool"), nativeReason=attempt["reason"],
                           providerOutcome=p["outcome"], providerFailure=p["failureCode"], providerRequestId=p.get("requestId"),
                           reportedModel=p.get("providerReportedModel"), usage=rr.get("usage"), audit=audits[a])
    want(set(rows) == set(decisions["dispatches"]), "外发三件套没有穷尽累计原生日志")
    return rows, feeds


def receipt_rows(node):
    if isinstance(node, list):
        for value in node:
            yield from receipt_rows(value)
    elif isinstance(node, dict):
        if isinstance(node.get("accepted"), bool) and "position" in node and ("taskId" in node or "commandId" in node):
            yield node
        else:
            for value in node.values():
                if isinstance(value, (dict, list)):
                    yield from receipt_rows(value)


def business(state, event_reports):
    decisions = state["decisions"]
    tasks = {r["taskId"]: a for a, r in decisions["actionRefs"].items()}
    commands = {}
    for a in decisions["bindings"]:
        n = len(a.encode("utf-16-le")) // 2
        commands[f"dec:{n}:{a}:{n}"] = a
    result, seen, unbound = defaultdict(list), set(), []
    for dispatch in decisions["dispatches"].values():
        if dispatch.get("businessReject"):
            result[dispatch["actionId"]].append(dict(stage="dispatch", accepted=False, reason=dispatch["businessReject"]))
    for module, node in [*state.items(), ("events", event_reports)]:
        if module in {"decisions", "pending", "position", "timeMillis", "runId"}:
            continue
        for r in receipt_rows(node):
            task = r.get("taskId", "")
            base = task
            while base and base not in tasks:
                base = base.rpartition(":")[0]
            action = tasks.get(base) or commands.get(r.get("commandId"))
            row = dict(module=module, **select(r, "taskId", "commandId", "kind", "action", "position", "accepted", "reason", "event"))
            rid = (action, r["position"], task, r["accepted"], r.get("reason"))
            if rid not in seen:
                seen.add(rid)
                if action:
                    result[action].append(row)
                elif not r["accepted"]:
                    unbound.append(row)
    return result, unbound


def editorial_returns(state, marks):
    """逐份把编辑退稿、受限意见版、作者阅读和此后的正式版本串到同一组确认帧。"""
    info = state["information"]["information"]
    docs = {d["docId"]: d for d in info["documents"]}
    experiences = {r["readId"]: r for r in info["observations"]}
    returns = []

    def revision_position(rev):
        return marks[("revision", rev["storyId"], rev["revisionNo"], rev["status"], rev["atMillis"])]

    for desk in state["media"]["states"]:
        office = desk["policy"]["mediaOrganizationPartyId"]
        stories = {s["storyId"]: s for s in desk["stories"]}
        for returned in (r for r in desk["revisions"] if r["status"] == "RETURNED"):
            story = stories[returned["storyId"]]
            author = story["authorPartyId"]
            cut = revision_position(returned)
            note_id = returned.get("reviewNoteDocId")
            completed, active = [], []
            note = None
            if note_id:
                doc = docs[note_id]
                want(doc["audience"] == dict(kind="EDITORIAL_REVIEW", partyId=office, authorPartyId=author)
                     and doc["publisherPartyId"] == returned["actorPartyId"], "意见受众或发布者与该次退稿不一致")
                v, = doc["versions"]
                want(v["version"] == 1 and v["publishedAt"] == returned["atMillis"]
                     and returned["note"] == v["body"], "退稿与该份意见文档不一致")
                note = dict(docId=note_id, version=v["version"], publishedAt=v["publishedAt"],
                            readDurationMillis=v["readDurationMillis"])
                for r in info["closed"]:
                    if (r["docId"], r["version"], r["personPartyId"], r["reason"]) != (note_id, v["version"], author, "COMPLETED"):
                        continue
                    obs = experiences[r["readId"]]
                    want((obs["docId"], obs["version"], obs["personPartyId"], obs["completedAt"], obs["body"])
                         == (note_id, v["version"], author, r["closedAt"], v["body"]), "作者读毕与本人实际经历不符")
                    pos = marks[("read", r["readId"])]
                    want(pos >= cut and r["startedAt"] >= v["publishedAt"], "意见读毕早于退稿")
                    completed.append(dict(position=pos, **select(r, "readId", "startedAt", "dueAt", "closedAt")))
                completed.sort(key=lambda x:x["position"])
                active = [select(r, "readId", "startedAt", "dueAt") for r in info["active"]
                          if (r["docId"], r["version"], r["personPartyId"]) == (note_id, v["version"], author)]
            later = []
            for r in desk["revisions"]:
                if r["storyId"] != story["storyId"] or r["revisionNo"] <= returned["revisionNo"] or r["status"] not in {
                        "SUBMITTED", "APPROVED", "PUBLISHED"}:
                    continue
                pos = revision_position(r)
                want(pos > cut, "修订版早于被退稿版本")
                real = docs[r["docId"]]
                ver, = [v for v in real["versions"] if v["version"] == r["docVersion"]]
                if r["status"] == "PUBLISHED":
                    want(real["audience"]["kind"] == "PUBLIC" and ver["publishedAt"] == r["atMillis"]
                         and any((l["docId"],l["version"]) == (r["docId"],r["docVersion"]) for l in desk["lineage"]),
                         "发行缺相应公开版本或来源谱系")
                if not note:
                    timing = "NO_REVIEW_DOCUMENT"
                elif not completed:
                    timing = "NO_COMPLETED_AUTHOR_READ_AT_CUTOFF"
                else:
                    timing = "AFTER_AUTHOR_READ" if pos > completed[0]["position"] else "BEFORE_AUTHOR_READ"
                transition = dict(position=pos, relativeToAuthorRead=timing, **select(r,
                    "status", "revisionNo", "docId", "docVersion", "actorPartyId", "atMillis"))
                if r["status"] == "PUBLISHED":
                    transition["completedPublicReaders"] = sorted({read["personPartyId"] for read in info["closed"]
                        if (read["docId"],read["version"],read["reason"]) == (r["docId"],r["docVersion"],"COMPLETED")})
                later.append(transition)
            later.sort(key=lambda r:r["position"])
            returns.append(dict(storyId=story["storyId"], office=office, author=author, editor=returned["actorPartyId"],
                                returnedRevision=returned["revisionNo"], returnedPosition=cut, returnedAt=returned["atMillis"],
                                reviewDocument=note, authorCompletedReads=completed, authorActiveReads=active, laterVersions=later))
    returns.sort(key=lambda row:row["returnedPosition"])
    return returns


def editorial_counts(returns):
    next_submissions = Counter()
    editions = set()
    for row in returns:
        next_draft = next((r for r in row["laterVersions"] if r["status"] == "SUBMITTED"), None)
        next_submissions[next_draft["relativeToAuthorRead"] if next_draft else "NO_LATER_SUBMISSION"] += 1
        editions.update((r["docId"], r["docVersion"]) for r in row["laterVersions"] if r["status"] == "PUBLISHED")
    return dict(returnedRevisions=len(returns), reviewDocumentsIssued=sum(r["reviewDocument"] is not None for r in returns),
                notesCompletedByAuthor=sum(bool(r["authorCompletedReads"]) for r in returns),
                closedAuthorReadingSessions=sum(len(r["authorCompletedReads"]) for r in returns),
                firstLaterSubmissionByReadState=dict(next_submissions), distinctLaterPublicEditions=len(editions))


def communication(state, physical_rows, feeds, sources, editorial_marks):
    readings = state["information"]["information"]
    completed, active = defaultdict(list), defaultdict(list)
    for r in readings["closed"]:
        if r["reason"] == "COMPLETED":
            completed[(r["docId"], r["version"])].append(r["personPartyId"])
    for r in readings["active"]:
        active[(r["docId"], r["version"])].append(r["personPartyId"])
    native_feed = defaultdict(list)
    for f in state["groups"]["publication"]["frames"]:
        a = f["attemptId"]
        want(physical_rows[a]["actionId"] == f["actionId"], "目录呈现与原始决定不符")
        for e in f["entries"]:
            native_feed[(e["docId"], e["version"])].append((a, physical_rows[a]["person"]))
    want({k: Counter(v) for k,v in feeds.items()} == {k: Counter(v) for k,v in native_feed.items()},
         "冻结目录呈现与物理模型输入不一致")

    def reach(doc, version):
        key = (doc, version)
        return dict(feedAppearances=len(feeds[key]), feedPersons=sorted({p for a, p in feeds[key]}),
                    completedReads=len(completed[key]), completedPersons=sorted(set(completed[key])),
                    activeReaders=sorted(active[key]))

    docs = {d["docId"]: {v["version"]: v for v in d["versions"]} for d in readings["documents"]}
    media = []
    for desk in state["media"]["states"]:
        lineage = {(l["docId"], l["version"]): l["roots"] for l in desk["lineage"]}
        for s in desk["stories"]:
            rows = sorted((r for r in desk["revisions"] if r["storyId"] == s["storyId"]), key=lambda r: r["atMillis"])
            versions = {}
            for r in rows:
                key = (r["docId"], r["docVersion"])
                if key[1] > 0 and key not in versions:
                    want(key in sources, "没有找到该稿版产生时的真实来源 " + str(key))
                    want(key[1] in docs[key[0]], "该稿版尚未发表到资料层")
                    if r["docId"] == s["publicDocId"]:
                        want(key in lineage and lineage[key] == sources[key]["roots"], "公开根来源与发行版不符")
                    versions[key] = dict(docId=key[0], version=key[1], **sources[key], **reach(*key))
            media.append(dict(office=desk["policy"]["mediaOrganizationPartyId"], **select(s, "storyId", "template", "status",
                              "authorPartyId", "revisionNo", "draftVersion", "publicVersion", "approvedBy"),
                              transitions=[{k:v for k,v in row.items() if k != "note" or row["status"] not in
                                           {"APPROVED", "RETURNED"}} for row in rows], versions=list(versions.values())))
    posts = []
    for channel, items in (("platform", state["groups"]["publication"]["platform"]["posts"]),
                           ("group", state["groups"]["state"]["posts"] + state["groups"]["state"]["postVersions"])):
        for post in items:
            v = post.get("version", 1)
            doc = post["docId"]
            if post["status"] == "PUBLISHED":
                want(v in docs[doc] and post["publishedAt"] >= 0, "帖子缺少相应的正式资料版")
            publication = {}
            if channel == "platform" and post["status"] == "PUBLISHED":
                version = docs[doc][v]
                publication = dict(publicTitle=version["title"], publicBody=version["body"])
            posts.append(dict(channel=channel, version=v, **publication, **select(post, "docId", "status", "author", "groupId", "contentDigest",
                              "basedOnVersion", "declaredRefs", "originRefs", "publishedAt"), **reach(doc,v)))
    private = []
    for m in state["social"]["state"]["messages"]:
        read = completed[(m["docId"], 1)].count(m["to"])
        want(not read or m["status"] == "DELIVERED", "收件人读完但投递未成立")
        private.append(dict(**select(m, "messageId", "docId", "from", "to", "status", "sentAt", "statusAt"), recipientCompletedReads=read))
    returns = editorial_returns(state, editorial_marks)
    report = dict(stories=media, editorialReturns=returns, editorialReturnCounts=editorial_counts(returns),
                mediaReceiptCounts=dict(Counter(r["kind"] + "/" + r["reason"] for r in state["media"]["reports"].values())),
                posts=posts, privateMessages=private, deliveredPrivateMessages=sum(m["status"] == "DELIVERED" for m in private),
                deliveredPrivateRecipients=sorted({m["to"] for m in private if m["status"] == "DELIVERED"}),
                readPrivateRecipients=sorted({m["to"] for m in private if m["recipientCompletedReads"]}),
                allCompletedReadCount=sum(map(len, completed.values())), allCompletedReaders=sorted({p for v in completed.values() for p in v}))
    if source_accounting is not None:
        accounting = source_accounting(state, sources)
        if accounting is not None:
            report["sourceAccounting"] = accounting
    return report


def summarize(folder, segments=None):
    if segments is None: segments = list(envelopes(folder))
    result = segments[-1][1]
    editorial_marks = {}
    s, people, audits, sources, sha, events = journal(folder, result, editorial_marks)
    rows, feeds = physical(segments, s["decisions"], audits)
    receipts, unbound = business(s, events)
    by_person = {}
    for p in people:
        attempts = [r for r in rows.values() if r["person"] == p]
        actions = {r["actionId"] for r in attempts}
        rejects = {a:[v for v in receipts[a] if not v["accepted"]] for a in sorted(actions)}
        by_person[p] = dict(physicalRequests=len(attempts), distinctDecisions=len(actions),
                            technicalFailedAttempts=[r["attemptId"] for r in attempts if r["outcome"] == "TECHNICAL_FAILED"],
                            providerFailures=[r["attemptId"] for r in attempts if r["providerFailure"]],
                            businessRejectedActions={a:v for a,v in rejects.items() if v},
                            scheduledWithoutMatchedReceipt=sorted(a for a in actions if a in s["decisions"]["actionRefs"] and not receipts[a]))
    want(sum(r["physicalRequests"] for r in by_person.values()) == len(rows), "有外发无法归入开局人物")
    comms = communication(s, rows, feeds, sources, editorial_marks)
    return dict(runId=s["runId"], sourceCommit=folder.parent.name, finalSegment=folder.name, stop=result["probeStop"],
                position=s["position"], timeMillis=s["timeMillis"], journalSha256=sha, physicalRequestCount=len(rows),
                technicalFailedAttempts=sum(len(v["technicalFailedAttempts"]) for v in by_person.values()),
                businessRejectedActions=sum(len(v["businessRejectedActions"]) for v in by_person.values()),
                distinctActionOutcomes=dict(Counter(d["outcome"] for d in s["decisions"]["state"]["decisions"])),
                recordedToolSelections=dict(Counter(d["recordedTool"] for d in s["decisions"]["state"]["decisions"] if d.get("recordedTool"))),
                people=by_person, attempts=list(rows.values()), unattributedNegativeReceipts=unbound,
                communication=comms)


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("segment", type=Path, help="目标封卷目录，如 calibration-s001；自动核对全部前序段")
    cli.add_argument("output", help="本脚本目录内的新 JSON 文件名")
    args = cli.parse_args()
    dest = HERE / args.output
    want(dest.resolve().parent == HERE and dest.suffix == ".json" and not dest.exists(), "输出必须是本目录的新 JSON 文件")
    report = summarize(args.segment.resolve())
    with dest.open("x", encoding="utf8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(report["runId"], report["position"], "frames,", report["physicalRequestCount"], "physical requests;", dest.name)
