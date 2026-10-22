# Shared module guardrails

These rules apply in directories whose `AGENTS.md` links here.

- Leave these modules untouched by default. Implement product features in their own modules using existing
  contracts. Do not bundle changes to shared modules into a broad product PR.
- Change a shared module only for a concrete goal in that module. Language Platform can lead such work.
  Outside that team, keep changes tactical and specific to this module. Put them in a separate PR from broader
  product work.
- Before editing, tell the user the goal for the module and why an exception is needed. If that rationale is
  unclear, leave the module untouched. Warn that Language Platform review is needed.
- Under `# Motivation` in the PR, add `## Shared module change` apart from any product motivation. Explain
  the goal for this module, why the work belongs here, affected consumers, and compatibility risks. When
  prompted by product work, explain why product code and existing contracts cannot meet the need.
- Check [CODEOWNERS](../.github/CODEOWNERS) for the current area owner. Put this GitHub alert at the very start
  of the PR description, before `# What Does This Do`:

  ```markdown
  > [!IMPORTANT]
  > Language Platform review is needed for this shared module change. Please involve @DataDog/apm-lang-platform-java.
  ```
