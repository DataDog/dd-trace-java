# Shared module guardrails

These rules apply in directories whose `AGENTS.md` links here.

- Before editing, warn the user that the change affects shared code and needs Language Platform review.
- Keep behavior for one product in its product module or instrumentation. Change a shared module for a product
  feature only when product code and existing contracts cannot meet the requirement. Keep the shared change minimal.
- Under `# Motivation` in the PR, add `## Shared module change` apart from any product motivation. Argue why
  this module must change, why product code and existing contracts cannot meet the need, which consumers it
  affects, and any compatibility risks.
- Check [CODEOWNERS](../.github/CODEOWNERS) for the current area owner. Put this GitHub alert at the very start
  of the PR description, before `# What Does This Do`:

  ```markdown
  > [!IMPORTANT]
  > Language Platform review is needed for this shared module change. Please involve @DataDog/apm-lang-platform-java.
  ```
