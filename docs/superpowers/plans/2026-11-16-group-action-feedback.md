# Group Action Feedback Implementation Plan

> Implement with subagent-driven-development; Task25 accepted mechanism slice precedes this task.

Goal: truthful own group/platform BEGIN, FINISH and PUBLISH feedback without model/economy changes.

# Task26 — 本人群/平台动作的准确阶段反馈

Workspace G:/项目/java-stock-auction/logs/world-operating-loop. BASE filled by Root before dispatch after Task25 acceptance. Read AGENTS.md, docs/agent-memory/PROJECT_MEMORY.md and this directory's task-26-design-diagnosis.md. No full historical plan read required.

## Problem / authority
RoleActionFeedback does not know group/platform reports. After the actual retained ATT-71 platform.post is completed on the Task25 offline copy, lastActionResult remains UNKNOWN (logs/operating-cycle/task25-candidate-replay/after-publish-feedback.json). Publishing is real only in that diagnostic copy, not a new business trial. Fix this exact missing own-action evidence projection without changing economic rules or model choices.

## Design decision
Use schema-neutral committed task lineage. Add a focused GroupActionFeedback helper/overlay or optional overload retaining existing RoleActionFeedback signature and baseline output. Activate only after exact original GroupReport is bound to own ActionRef task/person/GROUP_BEGIN/due and observation cut. No exact original report -> no additional JSON mutation/callback reads; legacy bytes retained. Root inventory: no model/session/new decisions executed under post-Task25 candidate; only offline advance of original retained intent. Original 71-call prefix predates its first group completion. Do not claim arbitrary previously frozen UNKNOWN group histories migrate.

Resolve actual GROUP_TASK_V1 + TASK_SCHEDULED_V1 from CommitSource, never direct journal/state/frameAtOrNull. Parent prefix verification has journal/state null, and BranchCommitSource must expose inherited prefix. Capture observed snap.position/time, not runtime current position; never accept future report/commit/task evidence. Do not require inherited commits' runId to equal child's runId. Original exact event/commit command binds task:<id>; event task/body must match ref, person, commandId=DecisionRun.actionCommandId(actionId), report kind/time/action; honor existing RoleActionPayload binding if wrapper exists. Reuse WorldRuntime's groupBody parser via a narrow callback rather than permissive duplicate parsing.

Follow actual scheduled child edges only: BEGIN->FINISH->optional PUBLISH; each later completed PendingTask must equal the parent's exact scheduled PendingTask. Support occupied names and -2 suffixes without startsWith/canonical-name inference. Missing/ambiguous evidence -> UNKNOWN, never borrowed success. If child not completed, require exact pending task at observation cut. There are at most three stage commits, memoized per action/position for duplicate recentDecisions and lastActionResult.

Add package-internal nonSnapshotCommitAt(position) backed by existing WorldJournal.history non-snapshot index, forwarded by CommitSource implementations; List/Branch select exact position and strip snapshot only. No new authority/cache/persistent schema. It must not materialize all history or decode historical state for each row. Preserve checked/unchecked corruption/I/O propagation, do not swallow real I/O as UNKNOWN. Existing validators/restore remain mandatory.

## Projection contract
- Rejected BEGIN/FINISH/PUBLISH -> REJECTED, accepted=false, actual reason and exact result task/kind/time/position.
- Accepted BEGIN with exact FINISH pending -> IN_PROGRESS (no whole-action accepted=true).
- Accepted post/correction FINISH with exact PUBLISH pending -> IN_PROGRESS; QUEUED is not published.
- Accepted non-post terminal FINISH or actual accepted PUBLISH -> APPLIED, accepted=true.
- Missing expected child or terminal evidence -> UNKNOWN, no fabricated success. APPLIED publish does not imply presented/read/believed.
Keep all non-group JSON bytes and role privacy unchanged. No raw task/model text, journal path or global audit facts added to role inputs.

## Meaningful RED/GREEN and affected checks
1. Real decision platform.post BEGIN/FINISH/PUBLISH phase feedback + exact original ref/result identity + strict reopen; accepted group.join FINISH; rejected BEGIN and FINISH feedback.
2. Actual occupied child names force -2 and still resolve correct lineage; same-person/action/prefix lookalike or different command cannot be borrowed. Include missing/forged task event edge checks; pure binding tests may use compact events but are not autonomous business evidence.
3. Historical observed cut before later publication remains IN_PROGRESS; later suffix cannot leak. Freeze a real subsequent decision input then strict replay; exercise inherited parent prefix verification where journal is null and child source combines prefix/self.
4. No original report leaves existing input byte-exact; existing RoleActionFeedbackTest compatibility hashes remain. Accessor exact-boundary/branch checks and bounded lookup counts (no timing benchmark); corruption failures not silently ignored.
Retain genuine behavioral RED, final GREEN logs/XML/exits under logs/operating-cycle/task26-group-feedback. Run affected classes once at final; do not repeat Task25 unchanged checks unless needed by new shared behavior. No full suite, JavaDoc, paid calls or independent agents. Root owns one full integration and actual archived 71-call provenance replay.

## Ownership/report
Own only relevant source/tests/target; Root docs/logs. Allowed main files: RoleActionFeedback.java, new GroupActionFeedback.java, WorldRuntime.java, CommitSource.java, WorldJournal.java plus focused tests. Do not broaden to provider/economy/permissions or rewrite existing state formats. No subagents, no commit. Hidden child processes, Node helper run-maven.cjs.
Write full report task-26-report.md with exact changes, genuine RED/GREEN commands/exits/counts, covered boundaries, self-review, all source/test SHA256 and concerns. Return short status. Raise concrete ambiguity to Root while continuing independent preparation; do not ask user.
