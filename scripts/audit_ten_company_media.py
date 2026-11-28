"""管理员封卷报告。仅汇总所给确认截面的原生采编回执、资料版本、话轮及阅读收据。"""
import argparse
from collections import Counter
from copy import deepcopy
import hashlib
import json
from pathlib import Path

from audit_ten_company_run import checked_frames, checked_opening, decode, patch_field


POLICY = "registered-origin-groups-v1"


def require(ok, message):
    if not ok:
        raise ValueError(message)


def delivery_id(kind, organization, source, version, reader):
    digest = hashlib.sha256()
    for value in (kind, organization, source, str(version), reader):
        # Java String.length() 按 UTF-16 计，摘要正文按 UTF-8 计。
        digest.update(str(len(value.encode("utf-16-le")) // 2).encode("ascii") + b":")
        digest.update(value.encode("utf-8"))
    return "media-delivery:" + digest.hexdigest()


def source_accounting(state, revision_sources):
    """已确认状态和首次写入的逐版本来源快照。旧政策返回 None，无对应原生回执的数量为 None。"""
    opening = state["reportMediaOpening"]
    if opening["policy"] != POLICY:
        return None
    media = state["media"]
    info = state["information"]
    documents = {doc["docId"]: doc for doc in info["information"]["documents"]}
    versions = {(doc["docId"], v["version"]): v for doc in documents.values() for v in doc["versions"]}
    published = {(row["docId"], row["version"]) for desk in media["states"] for row in desk["lineage"]}
    closed = Counter((r["docId"], r["version"], r["personPartyId"]) for r in info["information"]["closed"]
                     if r["reason"] == "COMPLETED")
    # 与开局实际证券登记、章程第一版的发布任务逐一绑定。发表经办人可以相同。
    issuers = {}
    for issuer in opening["issuers"]:
        doc = "company-governance:" + issuer + ":1"
        receipt = info["reports"].get("opening-note-task:" + issuer)
        if (receipt and receipt["accepted"] and receipt["kind"] == "INFORMATION_PUBLISH_V1"
                and receipt["subjectId"] == doc and receipt["commandId"] == "opening-note:" + issuer
                and (doc, 1) in versions and receipt["timeMillis"] == versions[doc, 1]["publishedAt"]):
            issuers[doc, 1] = issuer
    turns = {}
    origins = Counter()
    participant_reads = Counter()
    for office, dialogue in state["interviews"].get("dialogues", {}).items():
        for t in dialogue["turns"]:
            key = t["docId"], t["docVersion"]
            doc = documents[key[0]]
            require(key not in turns and doc["audience"]["kind"] == "DIRECT"
                    and t["speakerPartyId"] == doc["publisherPartyId"], "话轮未绑定本人定向原件")
            tags = versions[key]["internalSourceIds"]
            kinds = [tag.split("=", 1)[1] for tag in tags if tag.startswith("interview-basis=")]
            require(len(kinds) == 1 and kinds[0] in ("READ_SOURCE", "DIRECT_OBSERVATION", "UNVERIFIED"),
                    "没有唯一的原生采访来源声明")
            kind = kinds[0]
            turns[key] = office, kind
            origins[kind] += 1
            if closed[key + (doc["audience"]["partyId"],)]:
                participant_reads[kind] += 1

    def source_type(key):
        if key in issuers:
            return "REGISTERED_ISSUER_CHARTER"
        if key in turns:
            return "INTERVIEW_" + turns[key][1]
        if key in published:
            return "PUBLISHED_STORY"
        doc = documents[key[0]]
        if key[0].startswith("company-governance:"):
            return "UNREGISTERED_CHARTER_VERSION"
        if key[0].startswith("media-delivery:"):
            return "SUBSCRIBER_COPY"
        if doc["publisherPartyId"] == "research-system":
            return "SYSTEM_PUBLISHED_ORIGINAL"
        return "OTHER_ORIGINAL"

    stories = {story["storyId"]: (story, desk["policy"]) for desk in media["states"] for story in desk["stories"]}
    by_position = {(v["confirmedAt"], doc): v for (doc, _), v in revision_sources.items()}
    submissions = []
    for r in sorted(media["reports"].values(), key=lambda row: row["position"]):
        if r["kind"] != "MEDIA_STORY_SUBMIT_V1":
            continue
        row = {k: r[k] for k in ("position", "timeMillis", "taskId", "storyId", "accepted", "reason")}
        row.update(counted=None, required=None)
        shortfall = r.get("sourceShortfall")
        if shortfall is not None:
            require(not r["accepted"] and r["reason"] == "INSUFFICIENT_SOURCES"
                    and type(shortfall["counted"]) is int and type(shortfall["required"]) is int
                    and 0 <= shortfall["counted"] < shortfall["required"], "来源不足回执不一致")
            row.update(counted=shortfall["counted"], required=shortfall["required"])
        if r["accepted"]:
            story, policy = stories[r["storyId"]]
            draft = by_position[r["position"], story["draftDocId"]]
            roots = [(x["docId"], x["version"]) for x in draft["roots"]]
            refs = [(x["docId"], x["version"]) for x in draft["evidence"]]
            require(len(roots) == len(set(roots)) and all(k in versions for k in roots + refs),
                    "已接稿版的来源缺失或原件根重复")
            groups = set()
            for key in roots:
                category = source_type(key)
                if category == "REGISTERED_ISSUER_CHARTER":
                    groups.add(("issuer", issuers[key]))
                elif category in ("INTERVIEW_DIRECT_OBSERVATION", "SYSTEM_PUBLISHED_ORIGINAL", "OTHER_ORIGINAL"):
                    groups.add(("person", documents[key[0]]["publisherPartyId"]))
                elif category not in ("INTERVIEW_UNVERIFIED", "UNREGISTERED_CHARTER_VERSION"):
                    raise ValueError("规范来源根中仍有未展开的转述")
            required = policy["enabledTemplates"][story["template"]]
            require(len(groups) == draft["sourceGroups"] >= required, "独立来源组与该次原生送审不符")
            row.update(counted=draft["sourceGroups"], required=required, originalDocumentVersions=len(roots),
                       originalKinds=dict(Counter(map(source_type, roots))), citedKinds=dict(Counter(map(source_type, refs))))
        submissions.append(row)

    releases = {}
    for r in state["trading"]["reports"].values():
        if r["kind"] != "MEDIA_RELEASE_OWN_TURN_V1" or not r["accepted"]:
            continue
        key = (r["subjectId"], 1)
        require(key in turns and documents[key[0]]["publisherPartyId"] == r["personPartyId"]
                and key not in releases and r["reason"] == "TURN_RELEASED", "派送授权未绑定原发言人")
        releases[key] = r["timeMillis"]
    deliveries = {}
    for doc in documents.values():
        if not doc["docId"].startswith("media-delivery:") or doc["audience"]["kind"] != "DIRECT":
            continue
        v = versions[doc["docId"], 1]
        tags = dict(t.split("=", 1) for t in v["internalSourceIds"] if "=" in t)
        if tags.keys() != {"kind", "organization", "source", "version"}:
            continue
        kind, office, reader = tags["kind"], tags["organization"], doc["audience"]["partyId"]
        key = tags["source"], int(tags["version"])
        if doc["docId"] != delivery_id(kind, office, *key, reader):
            continue
        if kind == "INTERVIEW_SUPPLEMENT":
            require(key in releases and turns[key][0] == office and releases[key] == v["publishedAt"]
                    and doc["publisherPartyId"] == documents[key[0]]["publisherPartyId"]
                    and v["body"] == versions[key]["body"], "附页未绑定获准的原话")
        elif kind == "PUBLIC_NOTICE":
            require(key in published and documents[key[0]]["audience"]["kind"] == "PUBLIC", "到刊通知没有已刊版本")
        else:
            continue
        counts = deliveries.setdefault(kind, Counter())
        counts["deliveredCopies"] += 1
        counts["copiesReadByRecipient"] += int(closed[doc["docId"], 1, reader] > 0)
        counts["copiesWhoseRecipientReadOriginalVersion"] += int(closed[key + (reader,)] > 0)
    return dict(policy=POLICY, submissions=submissions, interviewTurnsByDeclaredBasis=dict(origins),
                turnsReadByTheAddressee=dict(participant_reads), speakerAuthorizedTurns=len(releases),
                subscriberDeliveries={k: dict(v) for k, v in deliveries.items()})


def report(path):
    """从标准十公司已封卷的完整原件复算，返回物理摘要、截面与同一 sourceAccounting 对象。"""
    digest = hashlib.sha256()
    parent = hashlib.sha256(b"null").hexdigest()
    state, sources = {}, {}
    fields = ("timeMillis", "position", "runId", "media", "information", "trading", "interviews")
    position = 0
    for frame in checked_frames(path, digest):
        position = frame["position"]
        events = frame["events"]
        delta, = [decode(e["payload"]) for e in events if e["type"] == "STATE_DELTA_V1"]
        require(delta["version"] == "delta-v1" and delta["baseDigest"] == parent
                and delta["basePosition"] + 1 == position == delta["position"]
                and delta["runId"] == frame["runId"], "非同一封卷的连续差量")
        parent = delta["resultDigest"]
        patch = delta["patch"]
        if position == 1:
            manifest = checked_opening(frame)["manifest"]
            registry, = [decode(e["payload"]) for e in events if e["type"] == "OPENING_REGISTRY_V1"]
            require(patch["kind"] == "replace", "首帧缺开局状态")
            policy = manifest["dataVersions"].get("editorial-source-accounting")
            if policy != POLICY:
                fields = ("timeMillis", "position", "runId")
            state = {k: patch["value"][k] for k in fields}
            state["reportMediaOpening"] = dict(policy=policy, issuers=[s["issuerOrganizationId"] for s in registry["securities"]])
        else:
            require(frame["runId"] == state["runId"] and patch["kind"] == "object"
                    and not set(fields).intersection(patch["removed"]), "封卷模块或运行身份改变")
            for k in fields:
                if k in patch["changes"]:
                    state[k] = patch_field(state[k], patch["changes"][k])
        require(state["position"] == position and state["runId"] == frame["runId"], "状态截面与物理帧不同")
        for e in events:
            if e["type"] != "MEDIA_TASK_V1" or state["reportMediaOpening"]["policy"] != POLICY:
                continue
            task = decode(e["payload"])
            r = state["media"]["reports"][task["taskId"]]
            if not r["accepted"] or r["kind"] != "MEDIA_STORY_SUBMIT_V1":
                continue
            require(r["position"] == position and r["timeMillis"] == state["timeMillis"], "送审回执错位")
            story, = [s for d in state["media"]["states"] for s in d["stories"] if s["storyId"] == r["storyId"]]
            key = story["draftDocId"], story["draftVersion"]
            require(key not in sources, "同一采编草稿版重复收件")
            sources[key] = dict(confirmedAt=position, **deepcopy({k: story[k] for k in ("roots", "sourceGroups", "evidence")}))
    require(position > 0, "档案为空")
    result = dict(runId=state["runId"], position=position, timeMillis=state["timeMillis"], journalSHA256=digest.hexdigest())
    accounting = source_accounting(state, sources)
    if accounting is not None:
        result["sourceAccounting"] = accounting
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("journal", type=Path, help="从首帧开始的普通十公司封卷 world.journal")
    args = parser.parse_args()
    print(json.dumps(report(args.journal), ensure_ascii=False, indent=2))
