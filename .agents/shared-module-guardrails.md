# Shared module guardrails

These rules apply in directories whose `AGENTS.md` links here.

- Before editing, warn the user that the change affects shared code and needs Language Platform review.
- Keep behavior for one product in its product module or instrumentation. Change a shared module for a product
  feature only when product code and existing contracts cannot meet the requirement.
- Argue why the shared change belongs here, which consumers it affects, and any compatibility risks. Keep the
  shared change minimal.
- In a PR, put that argument under `# Motivation`. Check [CODEOWNERS](../.github/CODEOWNERS) for the current area
  owner. Under `# Additional Notes`, include this GitHub alert:

  ```markdown
  > [!IMPORTANT]
  > Language Platform review is needed for this shared module change. Please involve @DataDog/apm-lang-platform-java.
  ```
