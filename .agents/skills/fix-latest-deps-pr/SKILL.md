---
name: fix-latest-deps-pr
description: >-
  Triage and unblock the weekly "Update Gradle dependencies" PR when GitLab CI is red from `latestDepTest` breakages.
  Use when asked to "fix the latest deps PR", "unblock the gradle dependencies PR", "fix update-gradle-dependencies", or
  when given a GitLab pipeline id + PR number. Rolls back the conflicting module lockfiles to make CI green, then
  optionally ships per-module real fixes as separate PRs.
user-invocable: true
---

# Fix "Update Gradle dependencies" PR

The weekly GitHub Action `.github/workflows/update-gradle-dependencies.yaml` bumps all latest dependencies and opens up
to two PRs (core + instrumentation). These PRs can go red on GitLab CI because a newly-updated *latest* dependency is
incompatible with current `dd-trace-java` code — the failures surface as `latestDepTest` task failures.

This skill has two phases:

1. **Unblock (always):** roll back only the `gradle.lockfile`s of modules whose `latestDep*Test` failed, one commit per
   module, then push once. This restores CI so the (still-valuable) lockfile updates for the other modules can merge.
2. **Real fix (opt-in, per module):** actually make the code compatible with the new dependency version, verified
   locally, shipped as a separate PR off `master`.

Only failures of a module's **latest-dep source set** are in scope — its `latestDep*Test` suites *and* their
compile/resolution tasks (e.g. `compileLatestDep*`). Ignore all other red jobs (flaky, infra, unrelated test failures)
— do not touch them.

---

## Prerequisites

Require `glab`, `gh`, and `jq`. If any is missing or unauthenticated, report exactly what's missing and stop — don't
install it yourself or work around it; print the install command so the user can run it manually.

- **`glab` (GitLab CLI) — required, authenticated.** `glab auth status` must be green for `gitlab.ddbuild.io`. If it's
  missing, tell the user to install it manually (`brew install glab`, or the platform package manager).
- **GitHub CLI (`gh`) — required, authenticated.** Resolves, checks out, and opens PRs. Run `gh auth status` to verify.
- **`jq` — required.** Every GitLab API call is parsed with it; confirm it is on `PATH`.
- **Git remote `origin` with `master` — required.** The rollback baseline and the Phase 3 branch base come from the
  latest `origin/master` — always `git fetch origin master` first so they aren't computed against a stale local ref.
- **Module-specific credentials — conditional.** Some optional instrumentations are excluded from the Gradle build
  unless a property is set, so their project path is unresolvable — e.g. `akka-http-10.6` needs
  `ORG_GRADLE_PROJECT_akkaRepositoryToken`. Skip such a module with a note to the user rather than assuming the mapping
  is wrong.

Every `glab api` call below uses two fixed values: `--hostname gitlab.ddbuild.io` (the remote is GitHub, so `glab`
can't infer the host) and the project path `DataDog%2Fapm-reliability%2Fdd-trace-java` (use exactly this — the shorter
`DataDog/dd-trace-java` only 301-redirects and breaks `glab`).

---

## Phase 0 — Preflight

1. **Collect inputs.** Ask the user for the **GitLab pipeline id** and the **PR number**.

2. **Resolve the PR.**
   ```bash
   gh pr view <PR> --json number,headRefName,headRefOid,url,baseRefName,title
   ```
   Capture `headRefName` (branch) and `headRefOid` (head SHA). Sanity-check that `baseRefName` is `master` and the
   title/branch looks like the weekly dependency-update PR; if not, STOP and tell the user this isn't the PR the skill
   handles (the whole skill assumes an `origin/master` baseline and lockfile-only diffs).

3. **Record the broken head SHA** as session context — call it `ORIG_PR_HEAD`. Substitute the literal 40-char SHA into
   every command below rather than a shell variable (each command may run in a separate shell). Phase 3 needs it to
   retrieve the *broken* lockfiles after Phase 2 rolls them back.

4. **Check out the PR branch at its head.**

- If the worktree is **dirty**, STOP and ask the user to commit/stash/discard — this skill rewrites lockfiles in place
  and a checkout could clobber uncommitted work.
- If clean and not already on the PR branch, run `gh pr checkout <PR>` and tell the user you switched them.
- Verify `HEAD`, and if it doesn't match, sync to the PR head (with the user's OK, since `reset --hard` moves the branch
  and drops any local commits above it):
  ```bash
  git rev-parse HEAD   # must equal ORIG_PR_HEAD
  git fetch origin && git reset --hard <ORIG_PR_HEAD-sha>   # only if HEAD != ORIG_PR_HEAD
  ```

Do not triage or commit until `HEAD` equals the PR head — otherwise Phase 2 builds commits on the wrong tree.

5. **Sync the master reference** (rollback + Phase 2/3 base):
   ```bash
   git fetch origin master
   ```

---

## Phase 1 — Triage failed latestDep modules

1. **Enumerate the failed jobs** for the given pipeline. First confirm the pipeline is actually the PR's — its SHA must
   equal `ORIG_PR_HEAD`, or a wrong/stale id would roll back unrelated lockfiles; STOP if it doesn't match. Then list
   the failed jobs (quote the path — zsh globs the `?`; `--paginate` to span all pages):
   ```bash
   PROJ=DataDog%2Fapm-reliability%2Fdd-trace-java
   PIPE=<pipeline-id>
   glab api --hostname gitlab.ddbuild.io "projects/$PROJ/pipelines/$PIPE" | jq -er .sha   # must equal ORIG_PR_HEAD
   glab api --hostname gitlab.ddbuild.io --paginate \
     "projects/$PROJ/pipelines/$PIPE/jobs?scope=failed&per_page=100" \
     | jq -r '.[] | "\(.id)\t\(.name)\t\(.status)"'
   ```

2. **Extract failing Gradle tasks** from each failed job's full `/trace` (the `Execution failed for task '…'` line is
   mid-log, not in the tail). Fetch into a scratch dir; abort if any trace can't be fetched, or a broken module goes
   unidentified:
   ```bash
   PROJ=DataDog%2Fapm-reliability%2Fdd-trace-java
   LOGDIR=$(mktemp -d); trap 'rm -rf "$LOGDIR"' EXIT   # don't leave internal CI logs lying around
   JOBS=(<failed-job-id> <failed-job-id> ...)
   for J in "${JOBS[@]}"; do
     glab api --hostname gitlab.ddbuild.io "projects/$PROJ/jobs/$J/trace" > "$LOGDIR/$J.log" \
       || { echo "ERROR: trace fetch failed for job $J — abort" >&2; exit 1; }
   done
   grep -hoE "Execution failed for task '[^']*'" "$LOGDIR"/*.log | sort -u
   ```

- **Collect only** latest-dep tasks — a break surfaces as either a test-execution failure or a *compile/resolution*
  failure of the latest-dep source set (which fails before the test runs). Match any failing task whose name contains
  `latestDep` — e.g. `latestDepTest`, `latestDepForkedTest`, `compileLatestDepJava`. **Ignore** everything else
  (`:test`, `:forkedTest`, muzzle, infra, …).
- **Record the exact failing task name (s)** per module — don't assume `latestDepTest`; some modules define only
  `latestDepForkedTest`, and Phase 3 reuses the real name to verify.
- **Record the JVM** the job ran on (the job name encodes it, e.g. a `j17`/`jdk17` segment). Phase 3 reproduces with
  `-PtestJvm=<jvm>`. If a module failed on more than one JVM, record each — verify them all.
- A single job can contain multiple failing latest-dep tasks — collect them **all**, across all jobs, deduped by module.

3. **Map each Gradle task path → lockfile.** Strip the trailing `:<taskName>`, convert the remaining `:` to `/`, and
   append `/gradle.lockfile` — e.g. `:<module-path>:latestDepTest` → `<module-path-with-slashes>/gradle.lockfile`.
   Verify the derived path exists; if it doesn't (rare Gradle-path/dir mismatch), resolve it with
   `./gradlew -q :<project-path>:properties | grep '^projectDir:'` rather than guessing by leaf name.
   Then confirm the PR actually changed this lockfile; if it didn't, the failure is flaky/unrelated (rolling back would
   be a no-op) — exclude the module and flag it to the user:
   ```bash
   BASE=$(git merge-base HEAD origin/master)   # HEAD == ORIG_PR_HEAD (verified in Phase 0)
   git diff --quiet "$BASE" HEAD -- <module>/gradle.lockfile \
     && echo "UNCHANGED — exclude (flaky/unrelated)" || echo "changed — in scope"
   ```

4. **Report the triage** to the user before making any change: each failed latestDep module with its Gradle path,
   lockfile, failing task (s), and JVM (s).

---

## Phase 2 — Unblock (rollback lockfiles)

For **each** failed module, create **one commit** rolling its lockfile back to the pre-update state. Do **not** push
between commits.

**Approval model:** the rollback commits are local and fully reversible, so create them without prompting. The one
action that needs the user's go-ahead is the **push** (step 4) — where changes leave the machine and re-trigger CI.

1. Roll the module's lockfile back to the pre-update baseline. Run as **one block** — `BASE` must live in the same
   shell:
   ```bash
   BASE=$(git merge-base HEAD origin/master)
   M=<module>/gradle.lockfile
   if git cat-file -e "$BASE:$M" 2>/dev/null; then
     git checkout "$BASE" -- "$M"   # existed at BASE (common case — an update) → restore & stage
   else
     git rm "$M"                    # created by the update → rolling back means deleting it
   fi
   ```

2. Commit that single module's already-staged change (restore or deletion — no `git add` needed):
   ```bash
   git commit -m "temporary fix: rolled back conflicting latest dependencies for module: <gradle-task-path-without-task> to unblock PR merging"
   ```

3. Repeat for every failed module — one commit each.

4. **Verify, then push once** (after all commits exist). One diff per module confirms the lockfile now matches the
   baseline — this covers both cases (a restored file has no diff vs `BASE`; a correctly-deleted new file is absent in
   both `BASE` and `HEAD`, so also no diff):
   ```bash
   BASE=$(git merge-base HEAD origin/master)
   git diff --quiet "$BASE" HEAD -- <module>/gradle.lockfile \
     && echo "matches baseline" || echo "DIFF — investigate before pushing"
   ```
   Fix anything flagged. Then, with the user's go-ahead, push **explicitly** to the PR branch (`headRefName` from Phase
   0) so a missing/incorrect upstream can't send commits elsewhere:
   ```bash
   git push origin HEAD:<headRefName>
   ```

One push (not per commit) triggers a single CI run. Report the pushed commits and remind the user CI will re-run.

---

## Phase 3 — Real fix (opt-in, per module)

After unblocking, **ask** the user whether to create separate real-fix PRs (one per failed module). If no, stop and hand
them the module list. If yes, work modules **one at a time** — fully finish and verify one before starting the next.

For each module:

1. **Fresh branch off master.** Name it from the **sanitized fully-qualified Gradle path** (strip the leading `:`,
   replace every `:` with `-`), *not* the leaf — two modules can share a leaf (e.g. `grpc-1.5`) and collide:
   ```bash
   git fetch origin master
   git checkout -b fix/latest-dep-<sanitized-gradle-path> origin/master
   ```

2. **Reproduce the failure** by putting the module's lockfile into the *broken* PR-head state. Substitute the literal
   `ORIG_PR_HEAD` SHA. The update regenerates every lockfile and can *delete* one, so mirror whichever state it has:
   ```bash
   if git cat-file -e "<ORIG_PR_HEAD-sha>:<module>/gradle.lockfile" 2>/dev/null; then
     git checkout <ORIG_PR_HEAD-sha> -- <module>/gradle.lockfile   # present at PR head → restore
   else
     git rm <module>/gradle.lockfile                               # absent at PR head → reproduce the deletion
   fi
   ```

3. **Research the breaking change:**

- Full CI logs via the `/trace` fetch in Phase 1 step 2; download `reports.tar` for thread/heap dumps if it's a
  hang/crash.
- Read the failing module's source and tests.
- Diff the conflicting dependency old vs new: release notes, changelog, tags/diffs, decompile the new jar if needed. Use
  web-fetch / web-search for release notes and upstream docs.

4. **Implement the fix** so the module is compatible with the new version. Usually production/test source, but often
   also module-local `build.gradle` changes (constraints, forced versions, source-set config) or test
   fixtures/resources — keep those too. If the fix genuinely requires changes **outside** the failing module (a shared
   helper, a related version module), that's allowed with clear justification, but you must extend verification (step 5)
   to every module you touched. Match surrounding code style; follow repo test conventions.

5. **Verify locally — the latest-dep suite must go green WITHOUT breaking the base test set** (a separate source set).
   Discover what the module defines (don't assume `latestDepTest`):
   ```bash
   ./gradlew :<gradle-path>:tasks --group=verification
   ```
   Then, on the JVM (s) you recorded in triage (run each one that failed), require all to pass:
   ```bash
   ./gradlew :<gradle-path>:<recorded-latestDep-task> -PtestJvm=<recorded JVM>
   ./gradlew :<gradle-path>:test -PtestJvm=<recorded JVM>
   ```
   Also run any other verification task the module defines, and the `test` (and latest-dep) tasks of any **other**
   module you changed. Skipping `-PtestJvm` runs your default JVM and can falsely pass a JVM-specific break. Do not
   proceed while anything is red.

6. **Only when everything is green**, commit, push the branch, and open a **draft** PR off `master`.

   **Write a real, filled-in PR description — never a stub.** Base it on `.github/pull_request_template.md` (read that
   file, fill each section from what you found and did this run): **What Does This Do** (the change + dependency version
   it targets), **Motivation** (which `latestDep` upgrade broke and the API/behavior change that caused it),
   **Additional Notes** (tests run, links to release notes/upstream diff; omit if empty), and the **Contributor
   Checklist**.

   **Stage only the intended files** — never `git add -A`. The lockfile's step-2 state (restore *or* deletion) is
   already staged, so stage just the files you changed here and re-add the lockfile **only if it still exists**
   (re-adding a deleted path errors). **Confirm with the user before committing** — show the staged diff and the PR
   title/body, and get an explicit go-ahead (this pushes a branch and opens an external PR). Then:
   ```bash
   git add <changed files: module source/tests/build.gradle/fixtures + any justified cross-module files>
   [ -e <module>/gradle.lockfile ] && git add -- <module>/gradle.lockfile   # deletion already staged from step 2
   git status && git diff --cached   # review exactly what will be committed — show this to the user
   # → get the user's go-ahead here, then:
   git commit -m "<imperative summary of the fix for the new <dep> version>"
   git push -u origin fix/latest-dep-<sanitized-gradle-path>
   gh pr create --draft --base master \
     --title "<imperative, user-visible summary>" \
     --label "tag: ai generated" \
     --label "tag: dependencies" \
     --label "<one inst: or comp: label>" \
     --label "<one type: label>" \
     --body "$(cat <<'EOF'
   <the completed, real description — all sections above, incl. Contributor Checklist, with actual content>
   EOF
   )"
   ```
   Per repo PR conventions: draft first; always include `tag: ai generated`, at least one `comp:`/`inst:` label, and one
   `type:` label.

7. Return to a clean state for the next module:
   ```bash
   git checkout <PR-branch-or-master>
   ```

When done, summarize: unblock commits pushed, and for each module either the fix PR URL or that it was skipped.

---

## Guardrails

- **Scope:** only modules whose latest-dep source set failed. Phase 2 changes **only** those modules' `gradle.lockfile`s
  — never source code, never another module. Phase 3 may extend beyond the failed module only with clear justification,
  and verification must then cover every module you touched.
- **Approvals:** get the user's go-ahead before the Phase 2 push and before committing/opening any Phase 3 PR.
- Keep Gradle runs sequential.
