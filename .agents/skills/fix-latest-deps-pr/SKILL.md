---
name: fix-latest-deps-pr
description: >-
  Triage and unblock the weekly "Update Gradle dependencies" PR when its GitLab
  CI is red because updated latest dependencies broke `latestDepTest` builds.
  Use when asked to "fix the latest deps PR", "unblock the gradle dependencies
  PR", "fix update-gradle-dependencies", or when given a GitLab pipeline id + PR
  number for a red dependency-update PR. The unblock step rolls back only the
  conflicting module lockfiles (one commit per module, single push) to make CI green
  again. An opt-in real-fix step then attempts a per-module code fix for the new
  dependency version, tested locally, as a separate PR off master.
user-invocable: true
---

# Fix "Update Gradle dependencies" PR

The weekly GitHub Action `.github/workflows/update-gradle-dependencies.yaml` bumps
all latest dependencies and opens up to two PRs (core + instrumentation). These PRs
frequently go red on GitLab CI because a newly-updated *latest* dependency is
incompatible with current `dd-trace-java` code — the failures surface as
`latestDepTest` task failures.

This skill has two phases:

1. **Unblock (always):** roll back only the `gradle.lockfile`s of modules whose
   `latestDep*Test` failed, one commit per module, then push once. This restores
   CI so the (still-valuable) lockfile updates for the other modules can merge.
2. **Real fix (opt-in, per module):** actually make the code compatible with the
   new dependency version, verified locally, shipped as a separate PR off `master`.

Only `latestDep*Test` failures are in scope. Ignore all other red jobs (flaky,
infra, unrelated test failures) — do not touch them.

---

## Prerequisites

Verify these before starting. If a required item is missing, stop and tell the user
what to set up rather than working around it.

- **`ddci-mcp-prod` MCP server — required.** Must be installed and authorized in this
  session. Phases 1 and 3 depend on its tools (`getCIStatus`, `getJobErrorSummary`,
  `getJobLogs`). Confirm it is reachable early (e.g. a `getCIStatus` call succeeds); if
  the tools are absent or unauthorized, stop and ask the user to install/authorize it.
- **GitHub CLI (`gh`) — required, authenticated.** Used to resolve, check out, and open
  PRs (`gh pr view/checkout/create`). Run `gh auth status` if unsure.
- **Git remote `origin` with `master` — required.** The rollback baseline and the
  Phase 3 branch base come from `origin/master`.
- **Push / PR permissions — required for the push steps.** You must be able to push to
  the dependency PR branch (Phase 2) and create PRs off `master` (Phase 3).
- **GitLab API access — optional, Phase 3 only.** A token (e.g. via `ddtool`) to fetch
  untruncated job logs from the `/trace` endpoint and download `reports.tar` artifacts
  when the ddci summary/logs are insufficient. If unavailable, fall back to ddci
  `getJobLogs` pagination.

---

## Phase 0 — Preflight

1. **Collect inputs.** Ask the user for:
   - the **GitLab pipeline id** (used for cross-checking and for direct GitLab API
     log/artifact fetching in Phase 3), and
   - the **PR number** (source of truth for the branch and head commit).

2. **Resolve the PR.**
   ```bash
   gh pr view <PR> --json number,headRefName,headRefOid,url,baseRefName,title
   ```
   Capture `headRefName` (branch), `headRefOid` (head SHA).

3. **Record the broken head SHA.** Remember the `headRefOid` value as session context —
   refer to it below as `ORIG_PR_HEAD`. **Do not** rely on a shell variable to carry it:
   each command may run in a separate shell, so substitute the literal 40-char SHA
   directly into every command that needs it. Phase 3 needs this SHA to retrieve the
   *broken* lockfiles after Phase 2 has rolled them back.

4. **Ensure the branch is checked out.**
   ```bash
   git rev-parse --abbrev-ref HEAD
   ```
   If it is **not** the PR branch, ask the user whether to check it out. Only if they
   say yes:
   ```bash
   gh pr checkout <PR>
   ```
   If they say no, stop — the skill needs the branch checked out to proceed.

5. **Require a clean worktree.** This skill rewrites `gradle.lockfile`s in place, so any
   uncommitted local work could be silently discarded. Check first:
   ```bash
   git status --porcelain
   ```
   If the output is non-empty, **stop** and ask the user to commit, stash, or discard
   their changes before continuing. Do not proceed with a dirty worktree.

6. **Sync master reference** (needed for rollback + Phase 2 base):
   ```bash
   git fetch origin master
   ```

---

## Phase 1 — Triage failed latestDep modules

1. **Get CI status** using the PR head SHA (per the confirmed mapping: PR → head SHA
   → ddci):
   - `getCIStatus(commit_sha=<ORIG_PR_HEAD>, include_metadata=true)`
   - This returns the DDCI `request_id` and the `tasks` map. Sanity-check the
     returned pipeline/request against the user-provided pipeline id and note any
     mismatch out loud before continuing.

2. **Enumerate failed tasks** from the `tasks` map. For each failed task, capture its
   full `task_id` (the map key, e.g. `gitlab-<pipeline>-<b64>`) and its
   `latest_task_execution.native_id` (the GitLab job id = `task_execution_id`).

3. **Extract failing Gradle tasks** from each failed job:
   - Start with `getJobErrorSummary(request_id, task_id, task_execution_id)`.
   - If unclear, fall back to `getJobLogs(request_id, task_execution_id)`.
   - Grep the output for lines of the form:
     ```
     Execution failed for task ':dd-java-agent:instrumentation:openai-java:openai-java-3.0:latestDepTest'.
     ```
   - **Collect only** Gradle task paths whose final segment is a `latestDep` test task
     — i.e. matches `:latestDepTest`, `:latestDepForkedTest`, or any
     `:latestDep*Test` variant. **Ignore** everything else (`:test`, `:forkedTest`,
     muzzle, infra, etc.).
   - A single job can contain multiple failing latestDep tasks — collect them **all**,
     across all failed jobs. Dedupe.

4. **Map each Gradle task path → module dir → lockfile.** Strip the trailing
   `:<taskName>`, then convert `:` to `/`:
   - `:dd-java-agent:instrumentation:openai-java:openai-java-3.0:latestDepTest`
     → module `dd-java-agent/instrumentation/openai-java/openai-java-3.0`
     → lockfile `dd-java-agent/instrumentation/openai-java/openai-java-3.0/gradle.lockfile`
   - Verify the lockfile exists. If the path mapping fails (rare mismatch between
     Gradle project path and directory), locate it by the leaf module name:
     `find . -path '*<leaf>/gradle.lockfile'`.

5. **Report the triage** to the user: the list of failed latestDep modules, each with
   its Gradle path and lockfile, before making any change.

---

## Phase 2 — Unblock (rollback lockfiles)

For **each** failed module, create **one commit** rolling its lockfile back to the
pre-update state. Do **not** push between commits.

1. Determine the pre-update baseline (the lockfile as it was before the update commit):
   ```bash
   BASE=$(git merge-base HEAD origin/master)
   git checkout "$BASE" -- <module>/gradle.lockfile
   ```

2. Commit that single module's lockfile:
   ```bash
   git add <module>/gradle.lockfile
   git commit -m "temporary fix: rolled back conflicting dependencies to unblock PR merging

   Module: <gradle-task-path-without-task>"
   ```

3. Repeat for every failed module — one commit each.

4. **Push once, after all commits exist.** Confirm with the user, then:
   ```bash
   git push
   ```
   Pushing once (not per commit) triggers a single CI run. Report the pushed commits
   and remind the user CI will re-run on the PR.

---

## Phase 3 — Real fix (opt-in, per module)

After unblocking, **ask** the user whether to create separate real-fix PRs (one per
failed module). If no, stop and hand them the module list. If yes, work the modules
**one at a time** — fully finish and verify a module before starting the next.

For each module:

1. **Fresh branch off master:**
   ```bash
   git fetch origin master
   git checkout -b fix/latest-dep-<leaf-module> origin/master
   ```

2. **Reproduce the failure** by restoring the *broken* lockfile from the recorded PR
   head (the version with the new, breaking dependency). Substitute the literal
   `ORIG_PR_HEAD` SHA you noted in Phase 0 — do not use a shell variable:
   ```bash
   git checkout <ORIG_PR_HEAD-sha> -- <module>/gradle.lockfile
   ```

3. **Research the breaking change** — gather what you need:
   - Full CI logs via ddci `getJobLogs` (paginate with `offset`), or the GitLab API
     `/trace` endpoint for the untruncated log; download `reports.tar` for
     thread/heap dumps if the failure is a hang/crash.
   - Read the failing module's source and tests.
   - Diff the conflicting dependency's old vs new version: GitHub release notes,
     changelog, tags/diffs, and decompile the new jar if needed to see the API/behavior
     change. Use WebFetch/WebSearch for release notes and upstream docs.

4. **Implement the fix** in the module's production/test source so it is compatible
   with the new dependency version. Match surrounding code style; follow repo test
   conventions.

5. **Verify locally — the fix must make latestDep green WITHOUT breaking the base
   test set.** `latestDepTest` is a separate source/test set from `test`, so run both,
   plus every other test task the module defines:
   ```bash
   ./gradlew :<gradle-path>:latestDepTest
   ./gradlew :<gradle-path>:test
   ```
   Also run any other test tasks the module has (e.g. `forkedTest`, `latestDepForkedTest`).
   List the module's tasks with `./gradlew :<gradle-path>:tasks --group=verification`
   and run all relevant ones. Every one must pass. Do not proceed while anything is red.

6. **Only when everything is green**, commit, push the branch, and open a **draft** PR
   off `master`.

   **Write a real, filled-in PR description — never a stub.** Author it yourself from
   what you actually found and did in this run (the failing `latestDep` upgrade, the
   API/behavior change you researched, the code you changed, the tests you ran). Keep it
   **short** and use markdown to highlight coding stuff (backtick `identifiers` /
   `ClassName#method`, code fences for snippets, links to release notes). The `<…>`
   angle-bracket hints below are instructions for what to write — replace each one with
   concrete content; do not leave placeholders, `...`, or the hints themselves in the
   final body. Follow this template exactly:

   ```markdown
   # What Does This Do

   <1–3 sentences: the code change and the dependency version it targets>

   # Motivation

   <why: which `latestDep` upgrade broke, and the API/behavior change that caused it>

   # Additional Notes

   <optional: tests run, links to release notes/upstream diff, caveats — omit the
   section entirely if there is nothing to add>
   ```

   Compose the finished body first, then pass it to `gh`. **Stage only the intended
   files** — the restored `gradle.lockfile` plus the source/test files you changed —
   never `git add -A`. Verify the staged diff before committing so nothing unrelated
   sneaks in:
   ```bash
   git add <module>/gradle.lockfile <changed source/test files>
   git status            # confirm nothing unintended is staged or left unstaged
   git diff --cached     # review exactly what will be committed
   git commit -m "<imperative summary of the fix for the new <dep> version>"
   git push -u origin fix/latest-dep-<leaf-module>
   gh pr create --draft --base master \
     --title "<imperative, user-visible summary>" \
     --label "tag: ai generated" \
     --label "tag: dependencies" \
     --label "<one inst: or comp: label>" \
     --label "<one type: label>" \
     --body "$(cat <<'EOF'
   <the completed, real description here — the three sections above with actual content>
   EOF
   )"
   ```
   Per repo PR conventions: open as draft first; always include `tag: ai generated`,
   at least one `comp:`/`inst:` label, and one `type:` label.

7. Return to a clean state for the next module:
   ```bash
   git checkout <PR-branch-or-master>
   ```

Repeat for each remaining module. When done, summarize: unblock commits pushed, and
for each module either the fix PR URL or that it was skipped/deferred.

---

## Guardrails

- Touch only modules whose `latestDep*Test` failed. Never modify lockfiles or code for
  unrelated red jobs.
- Phase 2 (unblock) changes **only** `gradle.lockfile`s — never source code.
- Never push per-commit in Phase 2; batch into a single push.
- Never open a Phase 3 PR until `latestDepTest`, `test`, and all other module test
  tasks pass locally.
- Keep Gradle runs sequential.
