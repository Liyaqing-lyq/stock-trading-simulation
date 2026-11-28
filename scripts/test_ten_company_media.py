"""封卷报告层的兼容与空值规则，可用 python -B scripts/test_ten_company_media.py 独立运行。"""
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import struct
from tempfile import TemporaryDirectory
import unittest

from audit_ten_company_media import POLICY, report, source_accounting
from audit_ten_company_run import EDITORIAL_SOURCE_POLICIES


class EditorialAccountingTest(unittest.TestCase):
    def base(self):
        return dict(reportMediaOpening=dict(policy=POLICY, issuers=["a", "b"]),
                    information=dict(information=dict(documents=[], closed=[]), reports={}),
                    interviews=dict(dialogues={}), trading=dict(reports={}), media=dict(states=[], reports={}))

    def test_zero_is_a_native_receipt_and_unknown_stays_unknown(self):
        state = self.base()
        ref = dict(taskId="a", position=1, timeMillis=5, kind="MEDIA_STORY_SUBMIT_V1", storyId="s",
                   accepted=False, reason="INSUFFICIENT_SOURCES")
        rows = state["media"]["reports"]
        rows["a"] = dict(ref, sourceShortfall=dict(counted=0, required=2))
        rows["b"] = dict(ref, taskId="b", position=2)
        rows["c"] = dict(ref, taskId="c", position=3, reason="NOT_AUTHORIZED")
        report = source_accounting(state, {})["submissions"]
        self.assertEqual([(0, 2), (None, None), (None, None)], [(r["counted"],r["required"]) for r in report])
        rows["c"]["sourceShortfall"] = dict(counted=0, required=2)
        with self.assertRaises(ValueError):
            source_accounting(state, {})
        state["reportMediaOpening"]["policy"] = None
        self.assertIsNone(source_accounting(state, {}))

    def test_archive_preserves_unknown_shortfall_and_actual_zero(self):
        run = "web-ten-company-autonomy-v24"
        physical = self.base()
        del physical["reportMediaOpening"]
        physical.update(position=1, timeMillis=0, runId=run)
        content = json.dumps({"manifest":{"runId":run, "dataVersions":dict(EDITORIAL_SOURCE_POLICIES, **{"branch-profile-run":run})}})
        base = dict(kind="MEDIA_STORY_SUBMIT_V1", storyId="s", accepted=False, timeMillis=1)
        rejections = {
            "first":dict(base, taskId="first",position=2,reason="INSUFFICIENT_SOURCES",sourceShortfall=dict(counted=0,required=2)),
            "second":dict(base,taskId="second",position=3,reason="NOT_AUTHORIZED"),
        }
        parent = hashlib.sha256(b"null").hexdigest()
        archive = bytearray()
        for pos, rid in enumerate((None,"first","second"),1):
            events = []
            if pos == 1:
                events.extend([dict(type="RUN_MANIFEST_V1",payload=content),
                               dict(type="OPENING_REGISTRY_V1",payload=json.dumps({"securities":[]}))])
                patch = dict(kind="replace",value=physical)
            else:
                reports = {"first":rejections["first"]} if pos==2 else rejections
                patch = dict(kind="object",removed=[],changes=dict(position=dict(kind="replace",value=pos),
                    timeMillis=dict(kind="replace",value=1), media=dict(kind="replace",value=dict(states=[],reports=reports))))
                events.append(dict(type="MEDIA_TASK_V1",payload=json.dumps(dict(taskId=rid))))
            current = hashlib.sha256(json.dumps(patch).encode()).hexdigest()
            delta = dict(version="delta-v1",baseDigest=parent,resultDigest=current,runId=run,basePosition=pos-1,position=pos,patch=patch)
            parent = current
            events.append(dict(type="STATE_DELTA_V1",payload=json.dumps(delta)))
            body = json.dumps(dict(commandContent=content if pos==1 else "{}",position=pos,runId=run,events=events)).encode()
            archive.extend(struct.pack(">ii",len(body),~len(body)) + body + hashlib.sha256(body).digest())
        with TemporaryDirectory() as folder:
            path = Path(folder)/"world.journal"
            path.write_bytes(archive)
            result = report(path)
        self.assertEqual(3,result["position"])
        self.assertEqual(hashlib.sha256(archive).hexdigest(),result["journalSHA256"])
        rows = result["sourceAccounting"]["submissions"]
        self.assertEqual([("first",0,2),("second",None,None)],[(r["taskId"],r["counted"],r["required"]) for r in rows])

    def test_two_issuer_originals_can_have_the_same_filer(self):
        state = self.base()
        refs = []
        for org in ("a", "b"):
            doc = "company-governance:" + org + ":1"
            refs.append(dict(docId=doc,version=1))
            state["information"]["information"]["documents"].append(dict(docId=doc,publisherPartyId="filer",
                audience=dict(kind="PUBLIC"), versions=[dict(version=1,publishedAt=1)]))
            state["information"]["reports"]["opening-note-task:"+org] = dict(accepted=True,subjectId=doc,
                kind="INFORMATION_PUBLISH_V1",commandId="opening-note:"+org,timeMillis=1)
        state["media"]["states"].append(dict(lineage=[],stories=[dict(storyId="s",draftDocId="draft",template="CROSS_BORDER")],
                                                 policy=dict(enabledTemplates=dict(CROSS_BORDER=2))))
        state["media"]["reports"]["submit"] = dict(taskId="submit", position=7, timeMillis=8, kind="MEDIA_STORY_SUBMIT_V1",
                                                     storyId="s", accepted=True, reason="OK")
        sources = {("draft",1): dict(confirmedAt=7, roots=refs, evidence=refs, sourceGroups=2)}
        row = source_accounting(state, sources)["submissions"][0]
        self.assertEqual((2,2),(row["counted"],row["required"]))
        self.assertEqual({"REGISTERED_ISSUER_CHARTER":2},row["originalKinds"])
        self.assertNotIn("filer",str(row))
        broken = deepcopy(sources)
        broken["draft",1]["sourceGroups"] = 1
        with self.assertRaises(ValueError):
            source_accounting(state,broken)


if __name__ == "__main__":
    unittest.main()
