# 已核验历史帧时间复用 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** 移除媒体排程校验对已核验历史帧的重复完整解码。
**Architecture:** 在现有64条/8MiB信息缓存中加入固定时间元数据；只供媒体排程位置/时点检查使用；所有miss保留完整严格解码。
**Tech Stack:** existing Java/Jackson/JUnit, no dependencies.
**Spec:** ../specs/2026-11-16-verified-frame-time-design.md

## Global Constraints
- 完整真实commit指纹、source生命周期、runId/position认证不变；仅已完成权威核验的帧准入。
- 64条/8MiB信息载荷上限不变，额外最多64个long；不保留全世界对象。
- fallback/capturing不准入，不推进可信前沿；失配必须核验实际目标，不能复用同位置旧cursor。
- 不改业务规则、manifest/版本、角色输入或日志格式；不触碰正在运行及冻结的f8运行时与原失败证据。

## Review Focus
同position更换实际目标提交；hash自洽但原命令校验失败；capturing污染前沿；同毫秒不同位置；时间metadata与信息payload失配。下列真实运行/缓存边界测试分别覆盖。

### Task18: exact frame-time cache and media scheduling consumer
**Files:** src/main/java/edu/cufe/auction/world/HistoricalInformationCache.java, WorldRuntime.java; existing HistoricalInformationCacheTest/HistoricalInformationCacheRuntimeTest and a bounded media scheduling test if clearer.
**Interfaces:** remember gains final long frameTimeMillis; Long findTime(Object, WorldCommit); private Long verifiedFrameTimeAtOrNull(long) used only media scheduling check.
- [x] Add a real scripted-local media proposal acceptance fixture with observed cutoff p, resident verified cache, cursor>p, actual CommitSource ordered reads. First run baseline must show prefix replay via failed no-prefix assertion. ZeroHTTP, no wall-time threshold.
- [x] Add metadata exact-time/source/identity/eviction/oversize assertions, preserving Task12 invalid-history/refill regressions. Missing/wrong observation time and same-time/different-position must retain exact rejection/identity behavior.
- [x] Implement scoped metadata lookup and original complete fallback without re-admission; no wider refactor.
- [x] Run focused cache/runtime/media regression tests; compare direct fixture actual accepted state, reports and independent reopened original-command output. Root will separately replay frozen real trial; do not claim from unit tests alone.
- [x] Provide RED/GREEN raw XML/logs/exit codes and changed-file hash manifest in task18 report; release source/target ownership, no commit until root review package prepared.

Task18 source and focused deterministic proof independently reviewed PASS. Frozen real-trial candidate replay and formal integration remain Root-owned gates; no overall operating claim.
