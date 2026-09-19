# Java Feature Flags: organize as customer-standalone and SSI

Author: Leo Romanovsky  
Updated: Sep 19, 2026  
Status: integrated POC for Java Language Tools discussion; not a release candidate.

## Motivation and proposal

Customers must be able to adopt Feature Flags without changing their instrumentation choice. Some use the OTel Java agent and dual-ship telemetry. Others have no Java agent or telemetry collector.

Deliver two installation paths from one implementation in `dd-trace-java`: standalone `dd-openfeature`, and provider injection through `dd-java-agent`. Keep the existing repository, public provider API, Maven coordinates, and release process.

[Integrated Java POC #12576](https://github.com/DataDog/dd-trace-java/pull/12576) is the discussion baseline. It replaces the earlier stack as the proposed starting point. Both assemblies work in controlled dogfooding. The module layout remains provisional.

**Standalone publication must not block SSI. SSI certification must not block standalone.** SSI can be last in the extraction stack without coupling their release approvals.

## Customer paths and entrypoints

| Installation | Application entrypoint | Runtime owner |
| --- | --- | --- |
| No Java agent | Add `dd-openfeature`; register the Datadog provider. | Library owns configuration polling, evaluation, and direct product-event delivery. |
| OTel Java agent only | Register the same standalone provider. | Same library runtime; preserve the customer's OTel setup. |
| SSI | Depend on OpenFeature only; enable Feature Flags. | Attached `dd-java-agent` installs the provider and owns delivery. |
| Existing explicit registration with Datadog instrumentation | Keep provider registration. | Compatible provider/agent pairs use the agent runtime. |

`DD_FEATURE_FLAGS_CONFIGURATION_SOURCE=agentless` selects direct configuration. It does not mean the Java agent is absent. Standalone requires credentials and network access to the CDN and direct event intake, but no collector.

Remote Configuration (RC) remains an agent capability:

```shell
DD_FEATURE_FLAGS_CONFIGURATION_SOURCE=remote_config
DD_REMOTE_CONFIGURATION_ENABLED=true
```

RC requires `dd-java-agent` and a compatible Datadog Agent. The Datadog Agent holds the API key and proxies product events. Explicit RC must not silently fall back to direct polling. An OTel Java agent does not replace this RC client.

Exposures and aggregated flag-evaluation events use Datadog's event platform (EVP). They are independent of OTel export. `feature_flag.evaluations` is an additional OTel metric.

## Code organization: share implementation, separate assemblies

Use Java packages for related code. Use Gradle subprojects when dependency, classloader, or publication boundaries require them. An internal module does not require a separately published customer artifact.

The POC has the following ownership. This table records working code, not approval for eight permanent modules.

| Responsibility | Current POC location |
| --- | --- |
| OpenFeature adapter and hooks | `feature-flagging-api`; shared unbundled classes |
| Evaluation, parsing, and configuration state | `feature-flagging-core`; one evaluator implementation |
| Lifecycle, queues, deduplication, and aggregation | `feature-flagging-lib`; shared `ProviderRuntime` |
| Direct CDN and EVP transport | `feature-flagging-http` |
| Standalone composition and shaded publication | `feature-flagging-standalone`; produces `dd-openfeature` |
| RC, Agent proxy, tracing integration, and injection | `feature-flagging-agent` plus OpenFeature instrumentation |
| Shared payload identity and settings | Existing `feature-flagging-bootstrap` and `feature-flagging-config` |

```mermaid
flowchart LR
  M["Manual provider registration"] --> S["Standalone assembly"]
  I["Agent activation + OpenFeature use"] --> A["Agent assembly + injection"]
  S --> C["Shared evaluator, lifecycle, and events"]
  A --> C
  S --> D["Direct CDN + EVP"]
  A --> D
  A --> R["RC + Agent EVP proxy"]
```

The repository uses `-lib` for core implementation. There is no Java requirement for separate `core`, `lib`, and `http` projects. Consider consolidating them before production extraction. Preserve an evaluator-only artifact for injection; do not inject HTTP clients, parsers, or runtime threads into applications.

Standalone must exclude RC and Datadog tracing. The agent must not depend on standalone shading or publication. Bootstrap payloads remain active implementation dependencies, not only unused compatibility shims.

## What the POC established

The integrated branch fixes source-default activation, global disable, shared-consumer shutdown, matching-artifact payload identity, and late OTel SDK registration.

The simultaneous dogfood fixture passes **27/27 checks**. Both Java deployments refresh configuration and deliver both EVP streams without an Agent or collector. All dashboard rows resolve configured values. Missing flags still fail.

The wider controlled matrices pass **23/25 cases**. Two historical agent-1.64.0 cases remain limitations, including the missing activation bridge. Earlier sequential staging checks pass five direct/RC cases. Intake acceptance is not downstream analytics proof.

Compose attaches the agent to an OpenFeature-only application. This proves provider injection, not platform-managed SSI installation. Full-agent testing also required package-index separation and interface-before-implementation helper loading. Preserve those boundaries when simplifying modules.

## OTel contract and remaining constraints

The POC declares a normal, non-shaded OTel API 1.57.0 dependency. It bundles no SDK or exporters and does not register a global provider.

- Lazy `getOrNoop()` preserves late application SDK registration. This method requires API 1.57+. See the [OTel Java guidance](https://opentelemetry.io/docs/languages/java/api/).
- Customer dependency management can select an older API. A bounded API-1.51 probe disabled metrics without throwing; it does not establish full compatibility.
- OTel agent 2.17 works through detection of an internal agent helper. Approve this mechanism or choose a tested agent minimum.
- The POC uses the global OTel instance visible to its classloader. It does not accept a separate application-supplied instance. Metrics before SDK availability are not replayed.

Java Language Tools must choose normal API packaging versus an optional metrics adapter, supported API/agent versions, and the initialization contract. Evaluation and EVP delivery must remain independent of telemetry availability.

## Deprecations and compatibility

Keep RC and manual provider registration. Preserve local synchronous evaluation, customer-selected providers, explicit disable controls, and shared-consumer shutdown behavior.

| Retire | Gate |
| --- | --- |
| Experimental designation | Remove separately for each deliverable after its release gates pass. |
| Experimental enablement and span-enrichment setting names | Use `DD_FEATURE_FLAGS_ENABLED` and `DD_FEATURE_FLAGS_SPAN_ENRICHMENT_ENABLED`. Retain aliases and documented precedence during an agreed support window. |
| Duplicate evaluators, parsers, stores, and lifecycle implementations | Shared implementations replace them after parity validation. |
| Legacy bridge APIs and typed payloads | Define supported provider/agent pairs first. Retain compatible types until replacement and removal gates pass. |

Legacy enablement is not a simple rename. Migrate legacy `true` with both `DD_FEATURE_FLAGS_ENABLED=true` and `DD_FEATURE_FLAGS_CONFIGURATION_SOURCE=remote_config`. Keep RC prerequisites. Migrate legacy `false` to explicit global disable.

## Extraction and release plan

1. **Shared implementation:** Agree the smallest module layout. Extract evaluation/state and compatibility boundaries with no installation change.
2. **Shared runtime:** Extract lifecycle and event pipelines. Preserve transport policy, ownership, and mixed-installation behavior.
3. **Standalone:** Move publication and direct composition. Approve OTel packaging, supported dependencies, and external-consumer behavior.
4. **SSI:** Package provider/evaluator helpers independently of standalone publication. Validate actual platform attachment, disable behavior, and rollback.

Keep the integrated POC as the comparison baseline. Define supported provider/agent pairs and classloader scope before removing bridges. Give each deliverable separate owners and release gates.

The [evidence record](feature-flags-migration-poc.md) and [assembly notes](feature-flags-assembly-notes.md) contain details for review. Open decisions are module boundaries, OTel integration, compatibility windows, and the first platform SSI target.

