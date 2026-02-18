# Architecture of dd-trace-java

This document describes the high-level architecture of the Datadog Java APM agent.
If you want to familiarize yourself with the codebase, this is a good place to start.

## Bird's Eye View

dd-trace-java is a Java agent that auto-instruments JVM applications at runtime via bytecode manipulation.
It attaches to a running JVM using the `-javaagent` flag, intercepts class loading, and rewrites method bytecode
to inject tracing, security, profiling, and observability logic - all without requiring application code changes.

The agent ships roughly 120 integrations (about 200 instrumentations) covering popular frameworks
(Spring, Servlet, gRPC, JDBC, Kafka, etc.) and supports multiple Datadog products through a single agent jar:
**Tracing**, **Profiling**, **Application Security (AppSec)**, **IAST**, **CI Visibility**,
**Dynamic Instrumentation**, **LLM Observability**, **Crash Tracking**, **Data Streams**,
**Feature Flagging**, and **USM**.

The agent communicates with a local Datadog Agent process (or directly with the Datadog intake APIs)
to send collected telemetry.

## Startup Sequence

Understanding the startup sequence is key to understanding the project:

1. **`AgentBootstrap.premain()`** — The JVM calls this entry point. It runs on the application classloader
   with minimal logic: it locates the agent jar, creates an isolated classloader, and jumps to `Agent.start()`.
   This class must remain tiny and side-effect-free.

2. **`Agent.start()`** — Running on the bootstrap classloader, this method orchestrates everything:
   creates the agent classloader, reads configuration, determines which products are enabled
   (tracing, AppSec, IAST, profiling, etc.), and starts each subsystem on dedicated threads.

3. **`AgentInstaller`** — Installs the ByteBuddy `ClassFileTransformer` that intercepts all class loading.
   It discovers all `InstrumenterModule` implementations via service loading and registers their
   type matchers and advice classes with ByteBuddy.

4. **Product subsystems start** — Each enabled product (AppSec, IAST, CI Visibility, Profiling, Debugger, etc.)
   is started via its own `*System.start()` method, receiving shared communication objects.

## Codemap

### `dd-java-agent/`

The main agent module. Its build produces the final shadow jar (`dd-java-agent.jar`).

- **`src/`** — Contains `AgentBootstrap` and `AgentJar`, the true entry point loaded by the JVM's
  `-javaagent` mechanism. Deliberately minimal.

- **`agent-bootstrap/`** — Classes loaded on the bootstrap classloader. Contains `Agent` (the real startup
  orchestrator), decorator base classes (`HttpServerDecorator`, `DatabaseClientDecorator`, etc.),
  and bootstrap-safe utilities. Code here must not use `java.util.logging`, `java.nio`, or `javax.management`
  (see Bootstrap Constraints below).

- **`agent-builder/`** — ByteBuddy integration layer. Contains custom matchers (`DDElementMatchers`,
  `HierarchyMatchers`), the class transformer pipeline, and the `ignored_class_name.trie` that skips
  unsafe or pointless classes.

- **`agent-tooling/`** — The instrumentation framework. Key types:
  - `InstrumenterModule` — Base class for all instrumentation modules. Each declares a target system
    (Tracing, AppSec, IAST, Profiling, CiVisibility, USM, etc.) and one or more instrumentations.
  - `Instrumenter` — Interface with variants for type matching: `ForSingleType`, `ForKnownTypes`,
    `ForTypeHierarchy`, `ForBootstrap`.
  - `muzzle/` — Build-time and runtime safety checks. Verifies that the types and methods an
    instrumentation expects actually exist in the library version present at runtime.
    If they don't, the instrumentation is silently skipped.

- **`instrumentation/`** — All auto-instrumentations, organized as `{framework}/{framework}-{minVersion}/`.
  About 186 framework directories. Each contains an `InstrumenterModule` subclass, one or more `Instrumenter`
  implementations, advice classes, decorators, and helpers. See `docs/how_instrumentations_work.md` for details.

- **`appsec/`** — Application Security. Entry point: `AppSecSystem.start()`. Integrates the Datadog WAF
  (Web Application Firewall) to detect and block attacks in real-time.
  Hooks into the gateway event system to intercept HTTP requests.

- **`agent-iast/`** — Interactive Application Security Testing. Entry point: `IastSystem.start()`.
  Performs taint tracking: marks user input as tainted, propagates taint through string operations,
  and reports when tainted data reaches dangerous sinks (SQL injection, XSS, command injection, etc.).

- **`agent-ci-visibility/`** — CI Visibility. Entry point: `CiVisibilitySystem.start()`.
  Instruments test frameworks (JUnit, TestNG, Gradle, Maven, Cucumber) to collect test results,
  code coverage, and performance metrics.

- **`agent-profiling/`** — Continuous Profiling. Entry point: `ProfilingAgent`.
  Collects CPU, memory, and wall-clock profiles using JFR or the Datadog native profiler (`ddprof`).
  Uploads profiles to the Datadog backend.

- **`agent-debugger/`** — Dynamic Instrumentation. Entry point: `DebuggerAgent`.
  Enables live debugging (set breakpoints, capture snapshots), exception replay, code origin mapping,
  and distributed debugging — all without restarting the JVM. Driven by remote configuration.

- **`agent-llmobs/`** — LLM Observability. Entry point: `LLMObsSystem.start()`.
  Monitors LLM API calls (OpenAI, LangChain, etc.), tracking token usage, model inference, and evaluations.

- **`agent-crashtracking/`** — Crash Tracking. Detects JVM crashes and fatal exceptions,
  collects system metadata, and uploads crash reports to Datadog's error tracking intake.

- **`agent-otel/`** — OpenTelemetry compatibility shim. Allows applications using the OTel API
  to have their spans captured by the Datadog agent.

### `dd-trace-core/`

The core tracing engine. Key types:

- `CoreTracer` — The tracer implementation. Creates spans, manages sampling, drives the writer pipeline.
  Implements `AgentTracer.TracerAPI`.
- `DDSpan` / `DDSpanContext` — Concrete span and context implementations with Datadog-specific metadata.
- `PendingTrace` — Tracks all spans belonging to a single trace. When all spans finish, flushes the trace
  to the writer.
- `scopemanager/` — `ContinuableScopeManager`, `ContinuableScope`, `ScopeContinuation`. Manages the
  active span on each thread and supports async context propagation via continuations.
- `propagation/` — Trace context propagation codecs: Datadog, W3C TraceContext, B3, Haystack, X-Ray.
- `common/writer/` — The writer pipeline. `DDAgentWriter` buffers traces and dispatches them via
  `PayloadDispatcherImpl` to the Datadog Agent's `/v0.4/traces` endpoint. Also: `DDIntakeWriter` for
  direct API submission, and `TraceProcessingWorker` for async trace processing.
- `common/sampling/` — Sampling logic: `RuleBasedTraceSampler`, `RateByServiceTraceSampler`,
  `SingleSpanSampler`. Supports both head-based and rule-based sampling.
- `datastreams/` — Data Streams Monitoring. Tracks message pipeline latency across Kafka, RabbitMQ,
  SQS, etc.
- `tagprocessor/` — Post-processing of span tags: peer service calculation, base service naming,
  query obfuscation, endpoint resolution.

### `dd-trace-api/`

The public API. Contains types that application developers may interact with directly:
`Tracer`, `GlobalTracer`, `DDTags`, `DDSpanTypes`, `Trace` (annotation), `ConfigDefaults`.
Also houses all configuration key constants organized by domain: `TracerConfig`, `GeneralConfig`,
`AppSecConfig`, `ProfilingConfig`, `CiVisibilityConfig`, `IastConfig`, `DebuggerConfig`, etc.

### `internal-api/`

Internal shared API used across all agent modules but not part of the public API.
Key abstractions that instrumentations interact with:

- `AgentTracer` — Static facade for the tracer. Instrumentations call `AgentTracer.startSpan()`,
  `AgentTracer.activateSpan()`, etc.
- `AgentSpan` / `AgentScope` / `AgentSpanContext` — Internal span/scope/context interfaces.
- `AgentPropagation` — Context propagation interfaces (`Getter`, `Setter`) that instrumentations
  implement to inject/extract trace context from framework-specific carriers (HTTP headers, message
  properties, etc.).
- `InstrumentationContext` — Provides context stores for attaching data to instrumented objects
  (e.g., storing a span on a request object). Works by dynamically adding fields via bytecode.
- `gateway/` — The Instrumentation Gateway: an event bus (`InstrumentationGateway`, `SubscriptionService`,
  `Events`, `CallbackProvider`, `RequestContext`) that decouples instrumentations from product modules.
  Instrumentations fire events (request started, response body received, etc.),
  and products (AppSec, IAST) subscribe to react.

### `communication/`

HTTP transport to the Datadog Agent and intake APIs. Key type: `SharedCommunicationObjects`,
which holds shared `OkHttpClient` instances (with Unix domain socket and named pipe support),
agent URL, feature discovery, and the configuration poller. All product modules receive this
at startup.

### `remote-config/`

Remote configuration client. `DefaultConfigurationPoller` periodically polls the Datadog Agent
for configuration updates (AppSec rules, debugger probes, sampling rates, feature flags).
Uses TUF (The Update Framework) for signature validation.

### `telemetry/`

Agent telemetry. `TelemetrySystem` collects and reports which features are enabled,
which integrations loaded, performance metrics, and product-specific counters.
Each product registers periodic actions that collect domain-specific metrics.

### `components/`

Low-level shared components: `context` (context propagation primitives), `environment` (JVM/OS detection),
`json` (lightweight JSON handling), `native-loader` (native library loading), `yaml`.

### `utils/`

Shared utilities: `config-utils`, `container-utils`, `filesystem-utils`, `flare-utils`,
`queue-utils`, `socket-utils`, `time-utils`, `version-utils`, `test-utils`.

### `products/`

Additional product modules: `metrics/` (StatsD client and monitoring abstraction) and
`feature-flagging/` (server-side feature flag evaluation via remote config).

### `dd-trace-ot/`

OpenTracing compatibility layer. Wraps the Datadog tracer behind the OpenTracing API.

### `dd-smoke-tests/`

End-to-end smoke tests. Each test boots a real application with the agent jar attached and verifies
traces, spans, and product behavior. Covers Spring Boot, Play, Vert.x, Quarkus, WildFly, and more.

## Instrumentation Pattern

Every instrumentation follows the same pattern:

1. An **`InstrumenterModule`** subclass declares which product it serves (Tracing, AppSec, etc.),
   its integration name, helper classes, and context stores.

2. One or more **`Instrumenter`** implementations select target types via matchers
   (`ForSingleType`, `ForKnownTypes`, `ForTypeHierarchy`) and register method advice.

3. **Advice classes** use ByteBuddy's `@Advice.OnMethodEnter` / `@Advice.OnMethodExit` to inject
   bytecode before/after matched methods. Advice code runs in the application's classloader context.

4. **Decorator/helper classes** contain the actual tracing logic (start span, set tags, finish span).
   These are injected into the application classloader at runtime.

5. **Muzzle** validates at build time (and runtime) that the instrumented library's API matches
   what the instrumentation expects, preventing `NoSuchMethodError` at runtime.

Instrumentations are discovered via `@AutoService(InstrumenterModule.class)` (Java SPI).

## The Instrumentation Gateway

The gateway (`InstrumentationGateway`) is the central integration point between instrumentations
and product modules. It implements a publish-subscribe pattern:

- **Instrumentations** (e.g., Servlet, Spring) fire events like "request started", "request header received",
  "response body committed" through the gateway.
- **Product modules** (AppSec, IAST) subscribe to these events via `SubscriptionService` and react
  (e.g., run WAF checks, track tainted data).
- `RequestContext` carries per-request state across products using `RequestContextSlot`
  for type-safe storage.

This design allows adding new products without modifying existing instrumentations.

## Writer Pipeline

When a trace completes, it flows through:

1. `PendingTrace` collects all spans until the root span finishes.
2. `CoreTracer.write()` sends the trace to the `Writer`.
3. `DDAgentWriter` (the default writer) enqueues the trace in `TraceProcessingWorker`.
4. The worker serializes traces to MessagePack via `PayloadDispatcherImpl`.
5. The dispatcher sends batched payloads to the Datadog Agent's trace intake endpoint.
6. Sampling responses from the Agent are fed back to update sampling rates.

## Architectural Invariants

- **Bootstrap classloader isolation**: The agent jar is a shadow jar with all dependencies relocated
  (shaded) under `datadog.` prefixes. This prevents classpath conflicts with application dependencies.
  The agent classloader is separate from the application classloader.

- **No premain side effects**: Code running during `premain` must not use `java.util.logging`,
  `java.nio`, or `javax.management`. These trigger JVM initialization that can break applications
  that configure these subsystems after `main`. See `docs/bootstrap_design_guidelines.md`.

- **Advice is bytecode, not code**: Advice classes are templates whose bytecode gets copied into
  target methods. They cannot reference methods on their own class or the instrumentation class.
  They can only use bootstrap classpath types or injected helpers.

- **Context stores are not maps**: `InstrumentationContext.get()` attaches data to objects by adding
  fields via bytecode. The same object instance (identity, not equality) must be used for put and get.

- **Instrumentations must be safe to skip**: Muzzle ensures any instrumentation can be silently
  disabled if the target library version is incompatible. No instrumentation should cause the agent
  to crash or the application to break.

- **Product independence**: Each product (AppSec, IAST, Profiling, etc.) can be enabled/disabled
  independently. Products communicate through the gateway, not direct dependencies.

## Cross-Cutting Concerns

- **Configuration**: The `Config` singleton reads from system properties, environment variables,
  remote configuration, and config files. Keys are defined as constants in `dd-trace-api`
  (e.g., `TracerConfig`, `GeneralConfig`). Configuration is immutable after startup,
  except for values updated via remote configuration.

- **Logging**: Uses a shaded SLF4J that redirects to an internal logger. Never use `java.util.logging`
  in agent code. In advice classes, move logging to helper classes.

- **Formatting**: google-java-format enforced via Spotless (`./gradlew spotlessApply`).

- **Testing**: Unit tests use JUnit 5 or Spock 2. Instrumentation tests extend
  `InstrumentationSpecification`. HTTP server integrations share base test classes
  that enforce consistency. Smoke tests use the real agent jar against real applications.
  Flaky tests are annotated with `@Flaky` and skipped in CI by default.

- **Build**: Gradle with Kotlin DSL. The agent jar is built via `shadowJar` which relocates all
  dependencies. Instrumentations declare framework dependencies as `compileOnly` so they don't leak
  into the agent jar. Muzzle runs at build time to validate version compatibility.
