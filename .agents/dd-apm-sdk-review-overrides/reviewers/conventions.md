Override for `reviewers/conventions.md` (in the core skill folder) — read that file first, then this.

# Codebase conventions — dd-trace-java specifics

## The repo's stated rules

Start at **AGENTS.md § "Key documentation"** — that table is the index. Open the linked file for the topic under review; do not restate it here.

Also not in that table, and in scope for this lens:

- `.editorconfig` and `gradle/spotless.gradle` — the mechanically enforced format (google-java-format via Spotless). Human-facing write-up is **CONTRIBUTING.md § "Automatic code formatting"** and **§ "Static imports"**.
- `.github/pull_request_template.md` — PR body contract.
- `.github/CODEOWNERS` — new paths need an owner when this repo's existing pattern would assign one.
- `metadata/supported-configurations.json` — the config/integration registry CI validates (`validate_supported_configurations_v2_local_file` in `.gitlab-ci.yml`).
- `.agents/skills/apm-integrations/SKILL.md` (+ `references/`) — instrumentation authoring, including integration-name registration and the Groovy-test exception. Cite it; do not invoke it (see `.agents/dd-apm-sdk-review-overrides/repo-context.md`).

Bootstrap / advice constraints in **AGENTS.md § "Critical constraints"** belong to the design lens, not this one.

## Mechanical checks — run these, don't eyeball them

Check-mode only. Anything that would rewrite files is the author's to run; if a check fails, report it.

Read **AGENTS.md § "Code conventions"** and **CONTRIBUTING.md § "Automatic code formatting"** for the rules, then run the check against the changed modules:

```bash
./gradlew spotlessCheck                          # whole repo
./gradlew :path:to:module:spotlessCheck          # prefer this when the diff is scoped
# Do NOT run spotlessApply.
```

There is no eslint / `tsc` equivalent. Spotless *does* cover Markdown, but only under `gradle/spotless.gradle`'s `format 'markdown'` target: root-level `*.md`, `.github/**/*.md`, `src/**/*.md`, and `application/**/*.md`. Markdown outside those paths — e.g. under `.agents/skills/**` — is not covered; `.editorconfig` is what applies there. If Gradle or the JDK is missing, report `NOT VERIFIED (<reason>)` rather than eyeballing format.

## Config options — registration path

Read **docs/add_new_configurations.md**. It owns the steps, the files, source priority, and the `supported-configurations.json` schema. Do not restate them from memory; open that doc and check the diff against it.

Only the parts that doc does not state as a severity:

- A new `DD_*` / `dd.*` read that is missing from `metadata/supported-configurations.json` is a CI failure (`validate_supported_configurations_v2_local_file`), not a nit — Blocking.
- Integration *names* (the strings passed to `super(...)` / `instrumentationNames()`) also need entries there. That shape is in `.agents/skills/apm-integrations/references/supported-configurations.md`, not in `add_new_configurations.md`.

## Instrumentations and tests

- New instrumentation: **docs/add_new_instrumentation.md** (Gradle include, layout, class/package naming) plus **docs/how_instrumentations_work.md § "Naming"** and **§ "Files/Directories"**. Missing `:dd-java-agent:instrumentation:…` include in `settings.gradle.kts` is silent non-build — P0.
- Tests: **docs/how_to_test.md** (and **docs/how_to_test_with_junit.md** when the change is JUnit). **AGENTS.md § "Code conventions"** is the one-line summary; the how-to is the spec.
- New `.groovy` test files are blocked by CI unless the PR has `tag: override groovy enforcement`. Instrumentation tests are the intended exception — see `.agents/skills/apm-integrations/SKILL.md`.

## Commit and PR hygiene

Read **CONTRIBUTING.md § "Pull request guidelines"** (draft-first, title, labels, merge queue) and **AGENTS.md § "PR conventions"** (adds `tag: ai generated`). Those own the rules.

Only the parts not stated there:

- There is no `pr-title.yml` (or equivalent) that rejects a title. The title is a house rule plus changelog input, not a CI gate — flag a bad title, do not invent a missing-linter finding.
- There is no changelog file: the PR title is the release note. Audit the title and `tag: no release notes` rather than asking for a CHANGELOG entry.
- No in-repo rule mandates `gh --repo` flags or a fork-vs-branch policy; do not invent one.
