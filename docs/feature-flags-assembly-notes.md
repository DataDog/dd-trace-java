# Feature Flags assembly notes

Status: combined library implemented in the integrated POC on September 20, 2026; production review remains open.

## Start with the constraints

We need two outputs: a customer library and the existing Java agent.
Both must use one evaluator and one shared runtime implementation.
Neither output should require the other's distribution or publication.
Existing provider/agent compatibility and classloader boundaries still apply.

Java packages organize names and access.
Gradle projects organize compilation, dependencies, and build tasks.
JARs are artifacts assembled from class files and resources.
These are different boundaries. We do not need one project or published artifact per logical responsibility.
This proposal does not require Java Platform Module System descriptors (`module-info.java`).

The repository's [architecture guide](../ARCHITECTURE.md#products) calls `{product}-lib` the core implementation.
It does not define `core` and `lib` as separate architectural layers.
Gradle's [Java Library plugin](https://docs.gradle.org/current/userguide/java_library_plugin.html) is the normal build mechanism.
An `implementation` dependency stays on a consumer's runtime dependency graph; it is not automatically removed from a binary.

## Six product projects, one shared implementation

The existing product had API, bootstrap, config, lib, and agent projects.
The first integrated POC added core, HTTP, and standalone.
The current POC combines core and HTTP with lib. Only standalone remains a new product project.

| Boundary | Reason | Current organization |
| --- | --- | --- |
| API versus implementation | OpenFeature and OTel dependencies; application-visible provider helpers | Keep this boundary. Do not infer its dependency contract from the generic zero-dependency API convention. |
| Evaluation versus full runtime | The application adapter and injection need evaluation without polling or event delivery | One library project produces full and evaluator-only artifacts. |
| HTTP versus runtime interfaces | Transport policy must remain replaceable | Direct HTTP code and runtime interfaces share the library project. |
| Standalone versus agent | Different entrypoints, dependency exclusions, and publication | Keep separate assembly boundaries. |
| Bootstrap payloads | Shared class identity across loaders and historical provider/agent pairs | Keep until a tested compatibility transition removes the need. |
| Config | Existing settings resolution shared across assemblies | Reuse the existing boundary for this migration. Do not add another settings framework. |
| Instrumentation | Agent-specific transformation and helper injection | Keep in the existing OpenFeature instrumentation project. |

`feature-flagging-lib` now owns evaluation, configuration, lifecycle, events, and direct HTTP.
Existing Java packages and class names remain unchanged. This is a build-boundary change, not a runtime rewrite.
API, bootstrap, config, agent, and standalone remain separate projects.
The product now has six projects instead of eight, without another customer artifact.
The removed projects' source and tests moved into lib; their build scripts and lockfiles were removed.

## Preserve the real binary boundaries

The library produces two internal artifacts: its normal output and an evaluator-only JAR.
The existing `evaluatorElements` configuration exposes only `com/datadog/featureflag/core/**`.
This shows that project count and artifact count need not match.

Both the OpenFeature adapter and instrumentation select `feature-flagging-lib:evaluatorElements`.
That configuration publishes selected class files without the full library's transport dependencies.
The standalone assembly consumes the full library and a non-transitive adapter dependency.
It supplies application API dependencies explicitly and does not bundle the evaluator twice.
The agent subsystem excludes evaluator classes; instrumentation retains their existing location.
`SharedEvaluatorArtifactTest` checks the selected artifact and rejects full-runtime classes on the adapter classpath.

Agent package indexing routes whole packages.
The parser's `com.datadog.featureflag` package belongs to the Feature Flags subsystem.
Evaluator helpers belong to the instrumentation section and are defined in application classloaders.
Putting both copies of a package in different agent sections caused a runtime failure.
Moving interfaces after implementing helpers also caused a loading failure.

The consolidated project preserves those selected outputs and dependency rules.
The injected helper set does not become the whole product runtime.
Continue to test the complete agent, not only an unbundled helper JAR.

```text
Shared implementation source
  +-- standalone assembly --> shaded dd-openfeature
  +-- agent subsystem -----> configuration / transport / event runtime
  +-- selected helpers ----> OpenFeature adapter + evaluator in app loader
```

Minimal binaries depend on their reachable classes, dependencies, resources, and classloader placement.
The standalone assembly currently excludes RC, tracing implementation, bundled OpenFeature, and bundled OTel.
Its HTTP path still consumes broad internal configuration and communication projects.
Reducing that dependency reach is a separate task from merging directories.

Shading relocates implementation classes. Minimization removes classes considered unreachable.
Reflective entrypoints need explicit retention and artifact tests.
Do not use minimization as a substitute for narrow dependencies.

The first consolidation build retained RC accidentally: excluding a project from minimization also retains its dependency graph.
The standalone assembly now supplies the adapter and library JARs as Shadow minimization entrypoints through `apiJars`.
This retains product classes and follows their references without retaining the entire communication graph.
This Shadow task setting does not add Maven API dependencies or another Gradle project.
This follows [Shadow's project-exclusion behavior](https://gradleup.com/shadow/configuration/minimizing/).

Acceptance criteria for simplification:

- Same fixture results, lifecycle behavior, and direct/RC routing.
- Same manual registration and injection controls.
- Evaluator-only helper set; correct full-agent package routing and helper definition order.
- No RC, tracing implementation, OTel SDK, or exporters in the standalone distribution.
- No standalone publication task in the agent build graph.
- No new customer-visible artifacts or incompatible payload identities.
- Compare JAR contents and size before and after; do not infer a smaller binary from fewer projects.

## OTel constraints found during the POC

The published POM declares OpenFeature 1.20.1 and OTel API 1.57.0.
The OTel dependency is not shaded; the application resolves its API and context classes.
The POC does not install an SDK, exporters, or a global provider.

`FlagEvalMetrics` resolves the global instance lazily through `getOrNoop()`.
This avoids claiming a no-op global before the application registers its SDK.
The [API documentation](https://opentelemetry.io/docs/languages/java/api/) recommends this lookup and supports explicit instance injection.
The POC does not expose explicit instance injection.
Non-global SDKs and separate application classloaders therefore need a defined support contract.
Evaluations recorded before an SDK becomes available are not replayed.

`getOrNoop()` is available [since API 1.57.0](https://javadoc.io/static/io.opentelemetry/opentelemetry-api/1.57.0/io/opentelemetry/api/GlobalOpenTelemetry.html).
A normal POM dependency does not prevent customer dependency management from forcing an older version.
On September 19, an isolated probe ran the built `FlagEvalMetrics.record()` with API/context 1.57.0 and then 1.51.0.
With 1.57.0, metrics remained enabled and the global provider remained unset.
With 1.51.0, the method returned without throwing and set the metrics object's closed state.
The implementation catches the missing method as `LinkageError` and disables metrics.
This probe did not run a complete application or prove compatibility with all older versions.

OTel agent 2.17 predates the new lookup.
The POC detects its `ApplicationOpenTelemetry` helper before falling back to `GlobalOpenTelemetry.get()`.
This passed dogfood metric export, but the helper is an internal implementation detail.
A newer API alone does not establish compatibility with every OTel agent version.

Before release, decide:

1. Normal API dependency or optional metrics adapter.
2. Minimum API and supported agent versions, including customer BOM overrides.
3. Whether to accept an application-supplied OTel instance.
4. Whether to retain the older-agent helper probe.
5. How to report disabled metrics without affecting evaluation or EVP delivery.

No SDK and no collector is a supported product scenario.
No OTel API dependency is a different packaging choice and is not what this POC publishes.
