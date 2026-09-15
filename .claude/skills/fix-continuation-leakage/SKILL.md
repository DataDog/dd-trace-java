---
name: fix-continuation-leakage
description: >
  Diagnose and fix scope or continuation lifecycle failures in dd-trace-java instrumentation
  tests. Use when a test reports a continuation leak, double resolution, activation after resolve,
  or an unclosed scope, or when strictTraceWrites(false) appears to hide one. Reads the automatic
  diagnostic timeline, finds the broken lifecycle edge, fixes it, and explains it with a compact
  Mermaid diagram.
user-invocable: true
context: fork
allowed-tools:
  - Bash
  - Read
  - Edit
  - Glob
  - Grep
  - AskUserQuestion
---

# Fix continuation leakage

Read `.agents/skills/fix-continuation-leakage/SKILL.md` in full and follow it. That file is the
shared playbook for repository agents.
