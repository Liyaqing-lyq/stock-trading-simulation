# 治理事实索引只读对照 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 完成有来源事实索引的24次世界外对照。
**Architecture:** logs-only Java/JSON helpers consume frozen role inputs; source/runtime untouched.
**Tech Stack:** existing Java/Jackson/DeepSeek client, Node filesystem orchestration.
**Spec:** ../specs/2026-11-16-governance-fact-index-diagnostic-design.md

## Global Constraints
- 24 HTTP / 10 minutes / zero retries / zero world commands.
- 原臂逐字不变；新臂只新增governanceFactIndex，所有事实来自同一输入可解析路径。
- 模型和system取原版本；WAIT合法；不指定动作、金额、候选、选票或成功结果。
- 所有失败和原始数据保留在项目logs，不覆盖旧证据。

## Review Focus
引用不存在；开局值误当当前；新会规则越过执行器；自己摘要误作事实；外壳有效误当业务成功。均在预检或逐回复核验中检查。

### Task 17: Prepared inputs, fixed protocol and real readonly comparison
**Files:** logs/operating-cycle/governance-fact-index-diagnostic/* only; task17 report in existing master ledger.
**Interfaces:** consumes governance-paired-helper/prepared-f8b7127-v3/inputs.json, PLANNING_SYSTEM_TEXT and existing audited provider; produces immutable inputs, protocol, planned calls,24raw replies and graded report.
- [x] Prepare exact4cases and copied-value pointers; verify each reference, old-byte SHA, sole-new-field invariant, count and input bounds before HTTP.
- [x] Freeze24counterbalanced entries and compiled harness hashes; zero-call validation must pass.
- [x] Run once with strict24/10min/no-retry limits; preserve terminal status, raw calls/replies/provider metadata.
- [x] Validate existing reply shell and independently grade facts/permission reasoning; report limits, no world-execution claims.
- [x] Record outcome in master ledger and decide production work only from evidence.
