# Feature Flags migration POC

This branch is an integration prototype, not a production release candidate.
It does not replace or rewrite PRs #12250, #12251, or #12252.

## Baseline

- Master: `7b903a53644abc39f55b2fb21283546ae9801f35`.
- Imported combined prototype: `f5a7e45801c0c43ea03fb0f56eda415615186a58`.
- Existing dogfood evidence: `JAVA_FEATURE_FLAGS_ADVERSARIAL_VALIDATION_20260918.md` in the parent workspace.
- Preserve current parser, privacy, transport, and dependency fixes.

## Execution checklist

- [x] Create an isolated branch from current master.
- [x] Import the existing standalone and injection implementation.
- [x] Extract shared evaluation, parsing, and configuration state into core.
- [x] Separate direct HTTP, agent adapters, and the standalone distribution.
- [x] Implement activation, global disable, shared-consumer lifecycle, and bridge compatibility fixes.
- [x] Use a normal OTel API dependency for the POC without installing an SDK or exporters.
- [x] Preserve late application OTel SDK registration in a forked test.
- [x] Add stable span-enrichment naming and retain legacy aliases and bridge shims.
- [x] Build both distributions and generate the customer POM.
- [x] Run canonical, lifecycle, artifact-boundary, and injection tests.
- [ ] Run no-agent, OTel-only, injection, and RC dogfood cases against the same artifacts.
- [ ] Record actual SSI deployment and rollback separately from manual attachment.

## Non-negotiable behavior

Manual registration activates standalone delivery by default unless explicitly disabled.
Explicit RC never falls back to direct CDN polling.
RC and manual provider registration remain supported.
Closing one consumer must not stop another consumer's refresh.
An application-selected provider must not be replaced by injection.
Configuration and product events must work without an OTel collector.
Supported mixed installations must share compatible payloads and one runtime owner.

## Provisional decisions

The POC uses a non-shaded OTel API dependency. Java Language Tools must approve the release packaging contract.
Legacy setting names and bridge types remain compatibility shims. Their removal versions remain undecided.
Actual SSI certification needs a named deployment target. Manual `-javaagent` tests do not satisfy that requirement.

## Implemented division

| Module | Responsibility |
| --- | --- |
| `feature-flagging-bootstrap` | Shared payloads and runtime bridge. These types remain unshaded. |
| `feature-flagging-core` | Current UFC parser, configuration snapshot, and the single evaluator implementation. No OpenFeature or transport dependency. |
| `feature-flagging-api` | Unbundled OpenFeature adapter, hooks, and OTel API metrics. |
| `feature-flagging-lib` | Event queues, exposure deduplication, and evaluation aggregation. Inject transport, thread creation, and diagnostics through interfaces. |
| `feature-flagging-http` | Direct CDN polling and direct EVP composition. |
| `feature-flagging-agent` | RC, EVP proxy routing and fallback policy, agent diagnostics, and Datadog span enrichment. |
| `feature-flagging-standalone` | Standalone lifecycle and shaded `dd-openfeature` publication. |
| OpenFeature instrumentation | Inject the unbundled API and core into an OpenFeature-only application. |

```text
manual Provider registration                 agent provider injection
          |                                           |
          +-------------- OpenFeature API ------------+
                              |
                         shared core
                              |
                  shared event pipelines
                    /                  \
       standalone assembly           agent assembly
       CDN + direct EVP              CDN or RC + EVP routing
```

The direct intake factory was extracted from the existing communication factory.
It preserves endpoint validation, response compression, redirect control, and retry policy.
Standalone no longer creates shared agent communication objects.
An artifact test rejects bundled RC clients, tracing implementation, OpenFeature classes, and OTel classes.

## Compatibility and deprecation

- Keep RC and manual provider registration.
- Replace experimental setting names with stable names. Keep their legacy behavior as compatibility aliases.
- Add `DD_FEATURE_FLAGS_SPAN_ENRICHMENT_ENABLED`. Its explicit value wins over the experimental spelling.
- Keep bridge payloads unshaded until the Java team defines the supported provider/agent version window.
- Use one evaluator implementation. Do not retain a standalone evaluator fork.
- Reference-count standalone consumers within one runtime classloader. Closing one provider does not stop another.
- Use generation-scoped handles so an old handle cannot stop a replacement runtime.
- Use OTel API 1.57.0. Its non-registering global lookup permits late application SDK registration.
- Follow the repository's POM-only shadow-publication convention. Do not publish contradictory Gradle module metadata.

The POC does not establish arbitrary application-classloader isolation.
The Java team must approve the ownership scope and oldest supported mixed-artifact pair.
Historical agent 1.64.0 lacks the activation bridge used by the candidate provider.
Do not label that pair supported without a separate compatibility decision and test.

## Validation in progress

Feature Flags suites pass on JDK 11, including canonical fixtures and explicit injection forked tests.
Both distributions build on this branch. The generated POM retains `com.datadoghq:dd-openfeature`.
Its external compile dependencies are OpenFeature 1.20.1 and OTel API 1.57.0.
No SDK or exporter is declared.

The companion dogfood branch uses main `a9f046c7f0fa8e47b02d8ba3ac580048f0e7116b`.
It preserves the original failing baseline worktree and evidence.
Record controlled and live runtime results before calling this POC validated.
