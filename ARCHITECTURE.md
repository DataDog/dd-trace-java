# Architecture of dd-trace-java

High-level architecture of the Datadog Java APM agent.
Start here to orient yourself in the codebase.

## Bird's Eye View

dd-trace-java is a Java agent that auto-instruments JVM applications at runtime via bytecode manipulation.
It attaches to a running JVM using the `-javaagent` flag, intercepts class loading, and rewrites method bytecode
to inject tracing, security, profiling, and observability logic. No application code changes required.

Ships ~120 integrations (~200 instrumentations) covering major frameworks
(Spring, Servlet, gRPC, JDBC, Kafka, etc.) and supports multiple Datadog products through a single jar:
**Tracing**, **Profiling**, **Application Security (AppSec)**, **IAST**, **CI Visibility**,
**Dynamic Instrumentation**, **LLM Observability**, **Crash Tracking**, **Data Streams**,
**Feature Flagging**, and **USM**.

Communicates with a local Datadog Agent process (or directly with the Datadog intake APIs)
to send collected telemetry.

## Startup Sequence

1. **`AgentBootstrap.premain()`** — JVM entry point. Runs on the application classloader
   with minimal logic: locates the agent jar, creates an isolated classloader, jumps to `Agent.start()`.
   Must remain tiny and side-effect-free.

2. **`Agent.start()`** — Runs on the bootstrap classloader. Creates the agent classloader,
   reads configuration, determines which products are enabled, starts each subsystem on dedicated threads.

3. **`AgentInstaller`** — Installs the ByteBuddy `ClassFileTransformer` that intercepts all class loading.
   Discovers all `InstrumenterModule` implementations via service loading, registers their
   type matchers and advice classes.

4. **Product subsystems start** — Each enabled product is started via its own `*System.start()` method,
   receiving shared communication objects.

## Codemap

### `dd-java-agent/`

Main agent module. Produces the final shadow jar (`dd-java-agent.jar`) using a composite shadow jar
strategy. Each product module builds its own shadow jar, embedded as a nested directory inside the
main jar (`inst/`, `profiling/`, `appsec/`, `iast/`, `debugger/`, `ci-visibility/`, `llm-obs/`,
`shared/`, `trace/`, etc.). A dedicated `sharedShadowJar` bundles common transitive dependencies
(OkHttp, JCTools, LZ4, etc.) to avoid duplication across feature jars. All dependencies are relocated
under `datadog.` prefixes to prevent classpath conflicts. Class files inside feature jars are renamed
to `.classdata` to prevent unintended loading. See [`docs/how_to_work_with_gradle.md`](docs/how_to_work_with_gradle.md).

- **`src/`** — `AgentBootstrap` and `AgentJar`, the entry point loaded by `-javaagent`.
  Deliberately minimal.

- **`agent-bootstrap/`** — Classes on the bootstrap classloader: `Agent` (startup orchestrator),
  decorator base classes (`HttpServerDecorator`, `DatabaseClientDecorator`, etc.), and bootstrap-safe
  utilities. Visible to all classloaders, so instrumentation advice and helpers can use them directly.

  See [`docs/bootstrap_design_guidelines.md`](docs/bootstrap_design_guidelines.md)

- **`agent-builder/`** — ByteBuddy integration layer. Class transformer pipeline:
  `DDClassFileTransformer` intercepts every class load, `GlobalIgnoresMatcher` applies early
  filtering, `CombiningMatcher` evaluates instrumentation matchers, `SplittingTransformer`
  applies matched transformations. The `ignored_class_name.trie` is a compiled trie built at
  build time that short-circuits matcher evaluation for known non-transformable classes (JVM
  internals, agent infrastructure, monitoring libraries, large framework packages). When a class
  is unexpectedly not instrumented, check the trie first.

- **`agent-tooling/`** — Instrumentation framework. Key types:
  - `InstrumenterModule` — Base class for all instrumentation modules. Declares a target system
    (Tracing, AppSec, IAST, Profiling, CiVisibility, USM, etc.) and one or more instrumentations.
  - `Instrumenter` — Type matching interface: `ForSingleType`, `ForKnownTypes`,
    `ForTypeHierarchy`, `ForBootstrap`.
  - `muzzle/` — Build-time and runtime safety checks. Verifies that expected types and methods
    exist in the library version at runtime. If not, the instrumentation is silently skipped.

  See [`docs/how_instrumentations_work.md`](docs/how_instrumentations_work.md) and [`docs/add_new_instrumentation.md`](docs/add_new_instrumentation.md).

- **`instrumentation/`** — All auto-instrumentations, organized as `{framework}/{framework}-{minVersion}/`.
  Nearly 200 framework directories. Each follows the same pattern: an `InstrumenterModule` declares the
  target system and integration name, one or more `Instrumenter` implementations select target types
  via matchers, advice classes inject bytecode via `@Advice.OnMethodEnter`/`@Advice.OnMethodExit`,
  and decorator/helper classes contain the actual product logic. Instrumentations are discovered
  via `@AutoService(InstrumenterModule.class)` (Java SPI) and validated by Muzzle at build time.
  See [`docs/how_instrumentations_work.md`](docs/how_instrumentations_work.md) and [`docs/add_new_instrumentation.md`](docs/add_new_instrumentation.md) for details.

- **`appsec/`** — Application Security. Entry point: `AppSecSystem.start()`. Runs the Datadog WAF
  to detect and block attacks in real-time. Hooks into the gateway to intercept HTTP requests.

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
  Live breakpoints, snapshot capture, exception replay, code origin mapping.
  Driven by remote configuration.

- **`agent-llmobs/`** — LLM Observability. Entry point: `LLMObsSystem.start()`.
  Monitors LLM API calls (OpenAI, LangChain, etc.): token usage, model inference, evaluations.

- **`agent-crashtracking/`** — Crash Tracking. Detects JVM crashes and fatal exceptions,
  collects system metadata, and uploads crash reports to Datadog's error tracking intake.

- **`agent-otel/`** — OpenTelemetry compatibility shim. `OtelTracerProvider`, `OtelSpan`,
  `OtelContext` and other wrappers implement the OTel API by delegating to the Datadog tracer.
  Paired with instrumentations in `instrumentation/opentelemetry/` that intercept OTel API calls
  and redirect them to shim instances.

### `dd-trace-core/`

Core tracing engine. Grew organically and now also hosts product-specific features that depend on
tight integration with span creation, interception, or serialization. New code should go in
`products/` or `components/` instead. Core tracing types:

- `CoreTracer` — Tracer implementation. Creates spans, manages sampling, drives the writer pipeline.
  Implements `AgentTracer.TracerAPI`.
- `DDSpan` / `DDSpanContext` — Concrete span and context implementations with Datadog-specific metadata.
- `PendingTrace` — Collects all spans in a trace. Flushes to the writer when the root span finishes.
- `scopemanager/` — `ContinuableScopeManager`, `ContinuableScope`, `ScopeContinuation`. Active span
  per thread, async context propagation via continuations.
- `propagation/` — Trace context propagation codecs: Datadog, W3C TraceContext, B3, Haystack, X-Ray.
- `common/writer/` — Writer pipeline. `DDAgentWriter` buffers traces and dispatches via
  `PayloadDispatcherImpl` to the Datadog Agent's `/v0.4/traces` endpoint. `DDIntakeWriter` for
  direct API submission. `TraceProcessingWorker` for async processing.
- `common/sampling/` — Sampling logic: `RuleBasedTraceSampler`, `RateByServiceTraceSampler`,
  `SingleSpanSampler`. Supports both head-based and rule-based sampling.
- `tagprocessor/` — Post-processing of span tags: peer service calculation, base service naming,
  query obfuscation, endpoint resolution.

Non-tracing code that also lives here due to organic growth:

- `datastreams/` — Data Streams Monitoring. Tracks message pipeline latency across Kafka, RabbitMQ, SQS, etc.
- `civisibility/` — CI Visibility trace interceptors and protocol adapters. Hooks into the trace
  completion pipeline to filter and reformat test spans for the CI Test Cycle intake.
- `lambda/` — AWS Lambda support. Coordinates span creation with the serverless extension,
  handling invocation start/end and trace context propagation.
- `llmobs/` — LLM Observability span mapper. Serializes LLM-specific spans (messages, tool calls)
  to the dedicated LLM Obs intake format.

### `dd-trace-api/`

Public API. Types application developers may use directly: `Tracer`, `GlobalTracer`, `DDTags`,
`DDSpanTypes`, `Trace` (annotation), `ConfigDefaults`. Also houses all configuration key constants
by domain: `TracerConfig`, `GeneralConfig`, `AppSecConfig`, `ProfilingConfig`, `CiVisibilityConfig`,
`IastConfig`, `DebuggerConfig`, etc.

### `internal-api/`

Internal shared API across all agent modules (not public). Like `dd-trace-core`, grew organically
and now hosts interfaces for many products beyond tracing. New product APIs should go in
`products/` or `components/`. 

Core tracing abstractions:

- `AgentTracer` — Static tracer facade. Instrumentations call `AgentTracer.startSpan()`,
  `AgentTracer.activateSpan()`, etc.
- `AgentSpan` / `AgentScope` / `AgentSpanContext` — Internal span/scope/context interfaces.
- `AgentPropagation` — Context propagation interfaces (`Getter`, `Setter`) that instrumentations
  implement to inject/extract trace context from framework-specific carriers (HTTP headers, message
  properties, etc.).
- `Config` / `InstrumenterConfig` — Master configuration class and instrumenter-specific config,
  centralizing settings for all products. `InstrumenterConfig` is separated from `Config` due to
  GraalVM native-image constraints: in native-image builds, all bytecode instrumentation must be
  applied at build time (ahead-of-time compilation), so configuration that controls instrumentation
  decisions (which classes to instrument, which integrations to enable, resolver behavior, field
  injection flags) must be frozen into the native image binary. Runtime-only settings (agent
  endpoints, service names, sampling rates) remain in `Config`.

Cross-product abstractions:

- `gateway/` — Instrumentation Gateway: event bus (`InstrumentationGateway`,
  `SubscriptionService`, `Events`, `CallbackProvider`, `RequestContext`) decoupling
  instrumentations from product modules. Primarily used by AppSec and IAST to hook into
  the HTTP request lifecycle without modifying instrumentations.
- `cache/` — Shared caching primitives (`DDCache`, `FixedSizeCache`, `RadixTreeCache`) used
  throughout the agent.
- `naming/` — Service and span operation naming schemas (v0, v1) for databases, messaging,
  cloud services, etc.
- `telemetry/` — Multi-product telemetry collection interfaces (`MetricCollector`,
  `WafMetricCollector`, `LLMObsMetricCollector`, etc.).

Product-specific APIs that also live here:

- `iast/` — IAST vulnerability detection interfaces: taint tracking (`Taintable`, `IastContext`),
  sink definitions for each vulnerability type (SQL injection, XSS, command injection, etc.),
  and call site instrumentation hooks. About 60 files.
- `civisibility/` — CI Visibility interfaces: test identification, code coverage, build/test
  event handlers, and CI-specific telemetry metrics. About 95 files.
- `datastreams/` — Data Streams Monitoring interfaces: pathway context, stats points,
  and schema registry integration.
- `appsec/` — AppSec interfaces: HTTP client request/response payloads for WAF analysis,
  RASP call sites.
- `profiling/` — Profiler integration: recording data, timing, and enablement interfaces.
- `llmobs/` — LLM Observability context.

### `components/`

Low-level shared platform components. Not tied to any product, no external dependencies,
bootstrap-safe:

- `context` — Immutable context propagation framework. Provides `Context`, `ContextKey`,
  and `Propagator` abstractions for storing  and propagating key-value pairs across threads
  and carrier objects.
- `environment` — JVM and OS detection utilities. `JavaVersion` for version parsing,
  `JavaVirtualMachine` for JVM implementation detection (OpenJDK, Graal, J9),
  `OperatingSystem` for OS/architecture detection, and `EnvironmentVariables`/`SystemProperties`
  for safe access and mocking.
- `json` — Lightweight, dependency-free JSON serialization. `JsonWriter` for building JSON
  with a fluent API, `JsonReader` for streaming parsing.
- `native-loader` — Platform-aware native library loading with pluggable strategies.
  `NativeLoader` handles OS/architecture detection, resource extraction from JARs,
  and temp file management.

### `products/`

Self-contained product modules following a layered submodule pattern:

- `{product}-api/` — Public API interfaces, zero dependencies.
- `{product}-bootstrap/` — Data classes safe for the bootstrap classloader.
- `{product}-lib/` — Core implementation (shadow jar, excludes shared dependencies).
- `{product}-agent/` — Agent integration entry point (shadow jar).

Current products:

- `metrics/` — StatsD client and monitoring abstraction. Provides `Monitoring` interface with
  counters, timers, and histograms for internal agent metrics collection.
- `feature-flagging/` — Server-side feature flag evaluation driven by remote configuration.
  Implements the OpenFeature SDK, handles the Unified Feature Control (UFC) protocol,
  and tracks flag exposure per user/session.

### `communication/`

HTTP transport to the Datadog Agent and intake APIs. `SharedCommunicationObjects` holds shared
`OkHttpClient` instances (Unix domain socket and named pipe support), agent URL, feature discovery,
and the configuration poller. All product modules receive this at startup.

### `remote-config/`

Remote configuration client. `DefaultConfigurationPoller` periodically polls the Datadog Agent
for configuration updates (AppSec rules, debugger probes, sampling rates, feature flags).
Uses TUF (The Update Framework) for signature validation.

### `telemetry/`

Agent telemetry. `TelemetrySystem` collects and reports which features are enabled,
which integrations loaded, performance metrics, and product-specific counters.
Each product registers periodic actions that collect domain-specific metrics.

### `utils/`

Shared utilities, each in its own submodule:

- `config-utils` — `ConfigProvider` for reading and merging configuration from environment variables,
  system properties, properties files, and CI environment.
- `container-utils` — Parses container runtime information (Docker, Kubernetes, ECS).
- `filesystem-utils` — Permission-safe file existence checks that handle `SecurityException`.
- `flare-utils` — Tracer flare collection (`TracerFlareService`) that gathers diagnostics
  (logs, spans, system info) and sends them to Datadog for troubleshooting.
- `queue-utils` — High-performance lock-free queues (`MpscArrayQueue`, `SpscArrayQueue`) for
  inter-thread communication and span buffering.
- `socket-utils` — Socket factories (`UnixDomainSocketFactory`, `NamedPipeSocket`) for connecting
  to the local Datadog Agent via Unix sockets or named pipes.
- `time-utils` — Time source abstractions (`TimeSource`, `ControllableTimeSource`) for testable
  time handling and delay parsing.
- `version-utils` — Agent version string (`VersionInfo.VERSION`) read from packaged resources.
- `test-utils` — Testing utilities: `@Flaky` annotation, log capture, GC control,
  forked test configuration.
- `test-agent-utils` — Message decoders for parsing v04/v05 binary protocol frames in tests.

### `dd-trace-ot/`

Legacy OpenTracing compatibility library. Publishes a standalone JAR artifact (`dd-trace-ot.jar`)
that implements the `io.opentracing.Tracer` interface by wrapping the Datadog `CoreTracer`.
This is a pure library for manual instrumentation only — there is no auto-instrumentation or
bytecode advice.

### `dd-smoke-tests/`

End-to-end smoke tests. Each boots a real application with the agent jar and verifies traces, spans,
and product behavior. Covers Spring Boot, Play, Vert.x, Quarkus, WildFly, and more.
Core test hierarchy (Groovy/Spock):
- `ProcessManager` — Base. Spawns forked JVM processes with the agent via `ProcessBuilder`,
  captures stdout to log files, tears down on cleanup. `assertNoErrorLogs()` scans logs for errors.
- `AbstractSmokeTest` extends `ProcessManager` — Adds a mock Datadog Agent (`TestHttpServer`)
  receiving traces (v0.4/v0.5), telemetry, remote config, and EVP proxy requests. Polling helpers:
  `waitForTraceCount`, `waitForSpan`, `waitForTelemetryFlat`.
- `AbstractServerSmokeTest` extends `AbstractSmokeTest` — For HTTP server apps. Adds port
  management, waits for server port to open, verifies expected trace output.
