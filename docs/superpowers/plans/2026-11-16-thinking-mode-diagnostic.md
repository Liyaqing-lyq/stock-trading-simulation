# Thinking mode diagnostic Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development.

**Goal:** Isolate thinking enabled versus disabled on identical frozen role requests.
**Architecture:** logs-only diagnostic client derived from frozen f8 client; no production edits or world execution.
**Tech Stack:** existing Java/Jackson/HTTP client and server KeySlots.
**Spec:** ../specs/2026-11-16-thinking-mode-diagnostic-design.md

## Global Constraints
-18HTTP/10min/60sec per call/0 retries/0 world commands; first incomplete or failure stops.
-Flash only; 8192 output cap and high effort both arms; onlythinking.type changes.
-No production/target/frozen-file edits; no reasoning_content正文保存或输出; credentialonlyexistingauthheader.
-Exact request bodies, input/source hashes and safe provider metadata preserved; cost/weights UNKNOWN.

## Review Focus
Unmatched request fields;wrong mode sent;budget truncation mislabeled success;raw reasoning or credential leakage;reply intention mistaken for execution. Preflight/bodydiff and post-run rawmetadata review cover these.

### Task22A: Build and zero-call preflight
- [x] Create logs-only client/harness, consume Task21 exact inputs, clean exact18 schedule/protocol.
- [x] Generate true request bodies without reading keys; prove onlythinking.type differs, fixed endpoint/model/limits, source/input hashes, planned identity uniqueness.
- [x] Compile isolated classes with frozenclasspath; read preflight/compile outputs and report actualfiles/hashes. NO paid calls by worker; return ready toRoot.
### Task22B: Root real diagnosis
- [x] Seal final helper/input/protocol and run once with limits.
- [x] Preserve outcomes/usage/finalcontent; compare everyreply against same sourceinput.
- [x] Record limits and decide production mode work only from evidence.

实际结果：18HTTP全部完整，enabled6convene/3WAIT，disabled1convene/8WAIT；独立事实分级8通过/5谨慎/5矛盾（含签约预算门槛纠正），原审稿v1保留。未执行世界命令。诊断helper未触发tool_calls提前返回缺finish证据的保留缺口；新生产实现另测。
