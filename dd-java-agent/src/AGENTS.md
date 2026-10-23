# dd-java-agent/src

Agent entry point and jar loading code used during premain.

> [!NOTE]
> `dd-java-agent` both assembles the agent jar and owns startup code under `src`.
> This layout does not follow Idiomatic Gradle's separation of code modules and aggregation projects:
> startup code lives in the parent project rather than in a dedicated subproject.
> These instructions therefore live under `src` to cover startup code and its tests without applying
> the same guardrails to every product and instrumentation subproject.

Read and follow the [shared module guardrails](../../.agents/shared-module-guardrails.md).
