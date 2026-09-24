# Feature Flags migration POC

This branch is an integration prototype, not a production release candidate.
[Draft PR #12576](https://github.com/DataDog/dd-trace-java/pull/12576) is the Java-team discussion baseline.
It supersedes the earlier stack for this discussion. The old PRs remain unchanged.
Start with the [short RFC](feature-flags-migration-rfc.md) and [assembly notes](feature-flags-assembly-notes.md).

## Baseline

- Branch: `poc/java-feature-flags-migration` in a dedicated worktree.
- Master: `7b903a53644abc39f55b2fb21283546ae9801f35`.
- Imported combined prototype: `f5a7e45801c0c43ea03fb0f56eda415615186a58`.
- Existing dogfood evidence: `JAVA_FEATURE_FLAGS_ADVERSARIAL_VALIDATION_20260918.md` in the parent workspace.
- Preserve current parser, privacy, transport, and dependency fixes.

## Execution checklist

- [x] Create an isolated branch from current master.
- [x] Import the existing standalone and injection implementation.
- [x] Extract shared evaluation, parsing, and configuration state.
- [x] Separate agent adapters and the standalone distribution.
- [x] Combine evaluation, direct HTTP, and runtime code in one library project.
- [x] Implement activation, global disable, shared-consumer lifecycle, and matching-artifact bridge fixes.
- [x] Use a normal OTel API dependency for the POC without installing an SDK or exporters.
- [x] Preserve late application OTel SDK registration in a forked test.
- [x] Retain the experimental span-enrichment setting, legacy provider behavior, and bridge shims.
- [x] Build both distributions and generate the customer POM.
- [x] Run canonical, lifecycle, artifact-boundary, and injection tests.
- [x] Run no-agent, OTel-only, injection, and RC dogfood cases against the same artifacts.
- [ ] Record actual SSI deployment and rollback separately from manual attachment.

## Non-negotiable behavior

Manual registration activates standalone delivery by default unless explicitly disabled.
Explicit RC never falls back to direct CDN polling.
RC and manual provider registration remain supported.
Both installation paths support explicitly selected RC through a compatible Datadog Agent service.
Standalone RC does not require `dd-java-agent` or an OTel collector.
It requires the optional `dd-openfeature-remote-config` artifact. The default JAR excludes the RC client.
Closing one consumer must not stop another consumer's refresh.
An application-selected provider must not be replaced by injection.
Configuration and product events must work without an OTel collector.
Supported mixed installations must share compatible payloads and one runtime owner.

## Provisional decisions

The POC uses a non-shaded OTel API dependency. Java Language Tools must approve the release packaging contract.
Legacy setting names and bridge types remain compatibility shims. Their removal versions remain undecided.
Actual SSI certification needs a named deployment target. Manual `-javaagent` tests do not satisfy that requirement.

## Implemented division

The September 20 consolidation combines core/lib/HTTP into one implementation project.
The evaluator-only helper artifact, compatibility boundaries, and separate assemblies remain.

| Module | Responsibility |
| --- | --- |
| `feature-flagging-bootstrap` | Shared payloads and runtime bridge. These types remain unshaded. |
| `feature-flagging-api` | Unbundled OpenFeature adapter, hooks, and OTel API metrics. |
| `feature-flagging-lib` | Parser, configuration state, single evaluator, shared `ProviderRuntime`, event queues, and direct HTTP transport. Its evaluator-only artifact excludes parser, HTTP, and runtime classes. |
| `feature-flagging-config` | Shared settings resolution. |
| `feature-flagging-agent` | Agent runtime composition, EVP routing policy, diagnostics, and Datadog span enrichment. |
| `feature-flagging-standalone` | Standalone lifecycle, CDN/RC composition, and shaded `dd-openfeature` publication. |
| `feature-flagging-remote-config` | Optional RC client assembly and Agent-proxy delivery. Publishes `dd-openfeature-remote-config`; no provider or evaluator. |
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
       CDN or RC + EVP routing       CDN or RC + EVP routing
```

The direct intake factory was extracted from the existing communication factory.
It preserves endpoint validation, response compression, redirect control, and retry policy.
Standalone no longer creates shared agent communication objects.
Artifact tests reject RC in the default JAR and require it in the add-on.
Both artifacts exclude tracing implementation, OpenFeature classes, and OTel classes.
The Java agent keeps its existing RC path without depending on the add-on.

Both composition roots use `ProviderRuntime` for source and writer startup, rollback, and close-once shutdown.
Standalone owns reference-counted consumer handles. The agent owns process-lifetime activation and span enrichment.
Stopping an inactive assembly does not clear the other assembly's writer.

The library also produces an internal evaluator-only JAR for the adapter and injection.
The parser remains in the agent's Feature Flags subsystem. The evaluator remains in its instrumentation section.
This separation prevents the agent's package index from routing the subsystem to the wrong section.
Injection defines core interfaces before provider helpers that implement them.
An isolated-classloader test checks that order without falling back to application copies.

## Compatibility and deprecation

- Keep RC and manual provider registration.
- Use `DD_FEATURE_FLAGS_ENABLED` for product enablement and automatic provider installation. Keep legacy provider behavior.
- Keep `DD_EXPERIMENTAL_FLAGGING_PROVIDER_SPAN_ENRICHMENT_ENABLED`, off by default. No span-enrichment rename is part of this migration.
- Remove the separate trace-integration gate from provider installation. Product disable also prevents runtime startup through manual registration.
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
The candidate now reports this incompatibility with an actionable error.

OTel agent 2.17 predates the new global lookup. The POC detects its injected API bridge before using the older lookup.
This restores metric export without registering a no-op global provider in an uninstrumented application.
The detection uses an OTel agent helper class. Java Language Tools must approve this compatibility mechanism or select a supported agent minimum.

The core still uses the existing unshaded UFC payload types from bootstrap.
This preserves the current parser and cross-loader payload identity while the compatibility window remains undecided.
This POC does not remove those types or claim they are no longer implementation dependencies.

## Optional Remote Configuration: Sep 21, 2026

Java commit `0efcb0be687c46353b3f533b0b56dcd0e663c22a` keeps RC outside the default customer JAR.
Dogfood commit `91d290b031bb7fc06fd576a93b50219e191c29ee` passes **5/5 real staging RC scenarios and 76/76 assertions**.
The manifest is `local/java-validation/results/optional-rc-v2-staging/manifest.json` in the companion checkout.

| Application | RC owner | Add-on | Result |
| --- | --- | --- | --- |
| Manual registration, no Java agent | Standalone | Required | Pass |
| Manual registration, OTel Java agent 2.17 | Standalone | Required | Pass |
| OpenFeature only, Datadog provider injection | Java agent | Absent | Pass |
| Manual registration, stable settings with Datadog Java agent | Java agent | Absent | Pass |
| Manual registration, legacy enablement with Datadog Java agent | Java agent | Absent | Pass |

All cases evaluate existing staging flags and send exposures and evaluation events through the Datadog Agent's EVP proxy.
The application has no API key. Tests verify runtime ownership, cached evaluation during an RC interruption, polling recovery, and no CDN fallback.
No staging flag configuration was changed. This is HTTP/TCP proof, not native transport or platform SSI certification.
Intake acceptance does not prove downstream analytics, and this run does not prove a changed staging RC revision.

The first optional build passed only the three Java-agent cases.
Its reused RC factory read the absent Java-agent version resource and advertised `0.0.0`.
The standalone cases polled successfully but received no flag configuration.
Passing the owning SDK version to the existing factory restored both standalone paths.
A request-level test now checks the advertised version from the actual published JAR.
The original `optional-rc-v1-staging` failure record remains unchanged.

The same corrected artifacts pass four direct-delivery controls and all 13 assertions without the add-on.
Those results are in `local/java-validation/results/optional-rc-v2/manifest.json`.
External Gradle consumers verify the base POM excludes RC and the add-on POM selects the matching base version.
Neither graph exposes unpublished internal projects, a Java agent, an OTel SDK, or exporters.
Published-artifact tests reject RC in the base, duplicate classes between JARs, and provider/evaluator copies in the add-on.
They also verify missing-add-on errors, an inactive add-on in direct mode, and no CDN fallback when RC is unavailable.
Focused tests and the complete agent build pass. This is not a full CI or historical compatibility-matrix rerun.

An additional browser check uses the dogfood dashboard's **Agent / Remote Config** mode with these artifacts.
Standalone with the optional add-on and the agent-injected installation each pass **6/6 displayed Java flags** and report `READY`.
The runtime owners are `STANDALONE` and `AGENT`, respectively. Neither application has an API key.
All Java evaluation reasons are `STATIC` or `SPLIT`. Route counts confirm real RC polling, accepted evaluation events, and no CDN fallback.
The local dashboard EVP counters are not staging-intake counters. Other language applications were not started.
See the [dashboard validation record](https://github.com/ddoghq/ffe-dogfooding/blob/poc/java-feature-flags-migration/local/java-validation/README.md#agent-mode-dashboard-validation-2026-09-21).

| Artifact | Size | SHA-256 |
| --- | --- | --- |
| `dd-openfeature.jar` | 1,840,638 bytes | `99bf11355e5e6725bc73717ea755d585adda305ce352e133f7799570e1c60f0f` |
| `dd-openfeature-remote-config.jar` | 4,167,419 bytes | `ad63aed09d01613c203145eabb6c72adb077db129f3bbe9a4614156a38e7394f` |
| `dd-java-agent.jar` | 35,067,302 bytes | `a37aedee03c8fa3be73dc60520e3d034e8a36c915572ab0af45d99baaaa1df2e` |

The rejected all-in-one candidate was approximately 4.39 MB. The revised base remains approximately 1.84 MB.
Private dependencies increase the combined optional installation size. Further minimization and native dependency isolation remain release work.
Later documentation commits do not change these recorded artifact bytes.

## Product configuration controls: Sep 21, 2026

Java commit `135218006ae10d6f6edbecd3abdcbb6d8e507abe` retains the experimental span-enrichment name.
Provider injection uses the existing Feature Flags product gate instead of the generic tracing integration gate.
`DD_FEATURE_FLAGS_ENABLED=false` prevents injection and runtime startup through manual registration.
No explicit Feature Flags settings means no automatic installation; manual standalone registration still selects direct delivery.

The focused JDK 11 suites pass 528 tests. Both distributions rebuild successfully.
Dogfood commit `bdb491f2022f4d849e2bf5580e1692cf7cb7c7cc` passes 10/10 scoped scenarios and all 40 assertions.
These cover standalone defaults, no registration, product enablement alone, product disable in both assemblies, tracing independence, and legacy source precedence.
Positive cases verify runtime ownership, configuration refresh, and both EVP streams.
Disabled agent cases verify zero configuration requests, caller defaults, and no product events.
See the [dogfood validation record](https://github.com/ddoghq/ffe-dogfooding/blob/poc/java-feature-flags-migration/local/java-validation/README.md#product-switch-validation-2026-09-21).
This scoped run does not rerun the full matrix, staging, platform SSI, or CI.

## Module consolidation: Sep 20, 2026

These results describe the September 20 artifacts. They include an injection-only switch that the September 21 revision removes.
Do not use those results as validation of the revised controls.

The product now has six Gradle projects instead of eight.
All 14 source and test files moved from core/HTTP to lib are byte-for-byte unchanged.
The API and instrumentation consume lib's evaluator-only artifact, not its full runtime dependencies.
Two new artifact tests check that boundary.
All six product `check` tasks and OpenFeature instrumentation tests pass on JDK 11: 862 tests, no failures or skips.
The standalone artifact tests verify no-agent startup and exclusion of RC, tracing implementation, and bundled application APIs.
Both distributions were rebuilt from signed Java commit `93603ccffed8e1b9e41626658a401005c5f4ee79`.
The simultaneous dogfood fixture again passes 27/27 checks at dogfood commit `4d7db0fb03c25b5da7ec6389657b7192a998e332`.
Its manifest is `local/java-migration/results/modules-consolidated-v1/manifest.json` in the companion checkout.
The external Gradle consumer resolves the generated POM without agent or unpublished internal-project dependencies.
Full-agent content and integration-index checks pass. Its task graph still excludes standalone assembly and publication.
The wider controlled matrices rerun with the same artifacts: 11/12 primary cases and 12/13 supplemental cases pass.
All 25 scenario outcomes match the pre-consolidation baseline.
The candidate-provider/agent-1.64.0 and unchanged provider-1.64.0/agent-1.64.0 comparisons remain failures.
Lifecycle, OTel export and initialization order, injection controls, outages, and shutdown pass.
Use `local/java-validation/results/modules-consolidated-v1/manifest.json` and `modules-consolidated-v1-supplemental/manifest.json` for these runs.
No new staging or platform-managed SSI run was performed for this consolidation.

| Distribution | Before | After | Class comparison |
| --- | --- | --- | --- |
| `dd-openfeature` | 2,687,655 bytes; 1,659 classes | 1,836,799 bytes; 1,076 classes | All common classes are byte-identical. Minimization removes 584 classes; the retained product library adds `FeatureFlagEventType`. |
| `dd-java-agent` | 35,066,057 bytes; 17,604 classes | 35,065,617 bytes; 17,604 classes | Identical class entries and bytes. |

Consolidated distribution SHA-256 values:

```text
dd-openfeature.jar  4afe8186e4c952d1828321149af327ffcd890a72879f8fb28b9506dffea0e7d5
dd-java-agent.jar   a2dbdb54c80d2e74c5b29821ca10292fad167dc4c37c0ca07727b2056b1648df
```

Later documentation commits do not change these tested artifacts.
The remaining sections preserve the earlier runtime evidence by source revision.

## Validation before module consolidation

The scoped Feature Flags suites report 860 tests with no failures or skips on JDK 11.
They include canonical fixtures, shared lifecycle, publication boundaries, helper definition order, and explicit injection forked tests.
Both distributions build on this branch. The generated POM retains `com.datadoghq:dd-openfeature`.
Its external compile dependencies are OpenFeature 1.20.1 and OTel API 1.57.0.
No SDK or exporter is declared.
An external Gradle consumer resolves this POM without agent or unpublished internal-module dependencies.
Module-level `check` tasks pass for core, API, lib, HTTP, agent, and standalone.
The agent build graph contains no standalone build or publication task.

The companion dogfood branch uses main `a9f046c7f0fa8e47b02d8ba3ac580048f0e7116b`.
It preserves the original failing baseline worktree and evidence.
The final runtime artifacts use Java source `23c20094ebec8f3c960873792212d9143919b32f` and label `poc-v3`.
Their harness uses dogfood source `3a02727c127e2f683222c7f10e717aebf4a08827`.
The runtime matrix checks the actual `STANDALONE` or `AGENT` owner, not only successful evaluation.

| Runtime evidence | Result |
| --- | --- |
| Controlled matrix | 11 of 12 pass. The candidate-provider/agent-1.64.0 comparison fails as documented above. |
| Supplemental matrix | 12 of 13 pass. The unchanged provider-1.64.0/agent-1.64.0 direct-source control remains unready. |
| No Java agent | Evaluation, refresh, exposures, evaluation aggregates, and standalone ownership pass. |
| Default activation and disable | Registration without an explicit source works. Global disable and no registration produce no CDN requests. |
| Shared consumers | Closing one provider leaves the other provider refreshing. |
| OTel application SDK | Registration before and after provider startup works. Both test exporters receive metrics. |
| OTel agent 2.17 only | Evaluation, refresh, both EVP streams, and `feature_flag.evaluations` export pass. |
| Candidate mixed installation | Both candidate artifacts share agent runtime ownership. |
| Provider injection | An OpenFeature-only application passes with tracing enabled and disabled. |
| Injection controls | No activation, global disable, and integration disable produce no configuration requests or product events. |
| Manual registration with injection disabled | The candidate provider and candidate agent evaluate, refresh, and deliver both EVP streams under agent ownership. |
| Delayed readiness | Evaluations return the default before configuration arrives, then return the configured value. |
| Source and intake outages | Cached evaluation, source recovery, independent refresh, and resumed EVP delivery pass. |
| Collector outage | Configuration refresh and both EVP streams continue while both OTel receivers are stopped. |
| Final provider shutdown | Configuration polling stops. |
| Harness and dashboard | Wrong-value, withheld-refresh, and missing-event negative controls pass. The dashboard shows the expected configured value. |
| Staging direct paths | Standalone, OTel-only, and injected provider pass known-flag evaluation and both EVP intake acceptance checks. |
| Staging RC | Stable and legacy settings pass. The application has no API key. RC and both EVP streams use the Agent proxy. |
| RC outage | Cached evaluation and polling recovery pass. No direct fallback occurs. |

The five staging cases all pass. They read existing flags and do not change configuration.
Across both controlled matrices, 23 of 25 cases pass. The two failures involve the historical 1.64.0 agent.
The unchanged 1.64.0 pair also failed the earlier baseline; it does not establish a migration regression.
The released provider with the candidate agent passes with injection enabled.
That result does not prove the historical evaluator implementation ran or establish general old-provider compatibility.
Staging EVP evidence is HTTP acceptance, not downstream event queryability.
The controlled fixture proves changed-revision refresh. Staging does not prove a changed RC revision.

Frozen distribution SHA-256 values:

```text
dd-openfeature.jar  de9e82faddcd53ed431a3361087d40258a7c792b8814f18bf2646395906d7757
dd-java-agent.jar   23a88598be6ab74e5c7500c37ec17d70b3b2f5a249092670028ea7d24e276e20
```

The companion worktree stores exact manifests and logs under `local/java-validation/results/`.
Use `poc-v3/manifest.json`, `poc-v3-supplemental/manifest.json`, and `poc-v3-staging/manifest.json` for the final matrices.
The README in that directory contains reproduction commands.

Earlier runs remain under `poc-v1` and `poc-v2` in the companion worktree.
They captured the OTel-agent lookup gap, agent package-index collision, and injected-helper ordering error.
The first mixed-installation success did not prove agent ownership. Do not use it as evidence for that requirement.

## Before production extraction

### Simultaneous local deployments: Sep 19, 2026

The companion dogfood branch also runs standalone and injected Java deployments together in its normal dashboard.
Both use direct configuration and direct EVP delivery, without a Datadog Agent or telemetry collector.
The controlled fixture passes 27/27 checks at dogfood revision `28daba7c683063d3831b4c356de7f068c6813316`.
Java artifacts were prepared at `ba25269395db76a627be0217e90de4f6ae4edc44`; changes after runtime revision `23c20094eb` were documentation-only.
Both providers report READY and resolve all six rows under both selectable prefixes.
Configuration refresh, both event streams, missing-flag errors, and the no-agent injection negative control pass.
The dashboard explicitly labels local fixture data and does not hide evaluation errors.

This later build has separate artifact hashes from the earlier matrices:

```text
dd-openfeature.jar  6d7b68e74925791b935b8e2255b3550f450832f869397ceb396c2fb0157927ac
dd-java-agent.jar   73ca36200aa3ef5ec072b2703df4a606478f13d84786e402543f34129880a884
```

The reproduction commands and source manifest locations are in the companion `local/java-migration/README.md`.
Raw generated artifacts and local manifests are ignored, not published in either PR.
The simultaneous staging run has not run. Earlier sequential staging evidence does not replace it.
Compose-owned attachment does not certify platform-managed SSI installation or rollback.

### Remaining release decisions

1. Approve OTel API packaging and the older-agent compatibility mechanism.
2. Define the supported provider/agent version pairs and application-classloader scope.
3. Set alias and bridge deprecation windows. Keep RC and manual registration.
4. Select an actual SSI deployment target and prove attachment and rollback there.
5. Extract the combined library, standalone publication, and injection changes from this integrated POC.

SSI can remain the final PR in the extraction stack. Its release gate must remain separate from standalone rollout.
This POC does not certify platform SSI, arbitrary application servers, or the full repository CI matrix.
