# Decision model sensitivity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans.

**Goal:** Isolate requested model effects on the same existing role input.
**Architecture:** logs-only18-call paired probe, no world execution or input transformation.
**Tech Stack:** existing official DeepSeek client and audit evidence.
**Spec:** ../specs/2026-11-16-decision-model-sensitivity-design.md

## Global Constraints
- 18HTTP/10min/0retries/0worldcommands;2models×3cuts×3repeats.
- Only model changes;system,input,slot,maxOutputTokens2048 unchanged.
- Raw evidence retained;unknown weights/cost;WAIT allowed.

## Review Focus
Hidden prompt/input change;mislabelled model;truncated output;invalid tool parameter;intent counted as execution. Preflight and per-reply review cover these.

### Task21: paired model diagnosis
- [x] Build a clean exact-case protocol and exact original inputs; prove source hashes and paired-only-model change before HTTP.
- [x] Compile logs-only harness, seal actual files, zero-call validate18.
- [x] Execute once with evidence/stop limits, preserving provider failures without retry.
- [x] Compare all replies conservatively and decide next actual-world experiment from observed evidence.
