# Repository Guidelines

> **For AI Agents**: This file provides a navigation hub and quick reference. Each section includes "📖 Load when..." guidance to help you decide which detailed documentation files to load based on your current task.

## Project Structure & Module Organization

- dd-java-agent — Agent runtime and instrumentation modules.
  - agent-bootstrap — Bootstrap classes injected into the application classloader.
  - agent-builder — ByteBuddy agent builder and transformation logic.
  - agent-tooling — Instrumentation infrastructure and matchers.
  - instrumentation/ — 120+ integrations for frameworks, libraries, and protocols.
  - agent-profiling — Continuous Profiler components.
  - agent-debugger — Dynamic Instrumentation (live debugger).
  - agent-iast — Interactive Application Security Testing.
  - appsec — Application Security (WAF/RASP) components.
  - agent-ci-visibility — CI Visibility integration.
  - agent-jmxfetch — JMX metrics collection.
- dd-trace-api — Public manual instrumentation API.
- dd-trace-core — Core tracer implementation.
- dd-trace-ot — OpenTracing bridge.
- dd-smoke-tests — End-to-end smoke tests with real applications.
- docs — Product and developer documentation.
- gradle/, buildSrc/ — Gradle build configuration and custom plugins.
- build.gradle.kts, settings.gradle.kts — Root Gradle build files (Kotlin DSL).

## Architecture Overview

- Auto-instrumentation: Java agent hooks JVM bytecode using ByteBuddy to inject tracing logic into target methods.
- Managed tracer: Core tracer (dd-trace-core) handles spans, context propagation, and agent communication.
- Agent bootstrap: Classes loaded early into the bootstrap/system classloader to ensure visibility across all classloaders.
- Instrumentation modules: Each integration defines type/method matchers and advice transformations to instrument third-party libraries.
- Build system: Gradle with Kotlin DSL coordinates builds, multi-JVM testing, and dependency management.

## Agent Artifact

The agent is packaged as a single JAR file that applications attach via the `-javaagent` JVM flag:
- **dd-java-agent.jar**: All-in-one agent JAR containing all instrumentations, core tracer, profiler, and bootstrap classes.
- **Usage**: `java -javaagent:dd-java-agent.jar -jar yourapp.jar`
- **Manual API**: Applications can also reference `dd-trace-api` as a Maven/Gradle dependency for manual instrumentation.

## Agent Structure

- `dd-java-agent/agent-bootstrap` — Bootstrap classes visible to all classloaders
  - `instrumentation/api` — Public instrumentation API (AgentSpan, AgentTracer, etc.).
  - `instrumentation/decorator` — Base decorator classes (HttpClientDecorator, DatabaseClientDecorator, etc.).
  - `instrumentation/java` — Java standard library helpers (concurrent, streams, classloaders).
  - `datadog/trace/bootstrap` — Core bootstrap utilities (CallDepthThreadLocalMap, ContextStore, etc.).
- `dd-java-agent/agent-builder` — Agent builder and transformation pipeline
  - `bytebuddy` — ByteBuddy integration and agent listener.
  - `matcher` — Type and method matchers, ignored class tries.
- `dd-java-agent/agent-tooling` — Instrumentation infrastructure
  - `InstrumenterModule` — Base classes for instrumentations (Tracing, Profiling, AppSec, Iast, CiVisibility, Usm, ContextTracking).
  - `Instrumenter` — Type matching interfaces (ForSingleType, ForKnownTypes, ForTypeHierarchy).
  - `AdviceTransformation` — Advice application and method matching.
  - `bytebuddy/matcher` — Matchers for types, methods, and classloaders.
  - `muzzle` — Compile-time safety checks for instrumentation compatibility.
- `dd-java-agent/instrumentation` — Framework integrations grouped by library
  - Examples: `akka`, `apache-httpclient`, `aws-java-sdk`, `cassandra`, `couchbase`, `elasticsearch`, `grpc`, `hibernate`, `http-url-connection`, `java-concurrent`, `jdbc`, `jms`, `kafka`, `lettuce`, `mongo`, `netty`, `okhttp`, `play`, `rabbitmq`, `ratpack`, `reactor`, `redis`, `servlet`, `spark`, `spring`, `vertx`, etc.
  - Each instrumentation typically includes:
    - `*Instrumentation.java` — Main instrumentation class with type/method matchers.
    - `*Advice.java` — ByteBuddy advice classes with @Advice.OnMethodEnter/@OnMethodExit.
    - `*Decorator.java` — Span decoration logic extending base decorators.
    - Helper classes for inject/extract adapters, listeners, wrappers.
    - `build.gradle` — Dependencies and muzzle directives.
    - `src/test/groovy` — Spock tests extending base test frameworks.
- Other agent modules
  - `agent-profiling` — Native and JFR-based continuous profiling.
  - `agent-debugger` — Dynamic instrumentation for live debugging.
  - `agent-iast` — IAST taint tracking and analysis.
  - `appsec` — Application Security WAF integration.
  - `agent-ci-visibility` — CI test instrumentation.
  - `agent-jmxfetch` — JMX metric collection.
  - `agent-otel` — OpenTelemetry bridge and exporters.

<details>
<summary>Detailed Agent Structure (Tree View + Component Details)</summary>

```
dd-java-agent/
├─ agent-bootstrap/        ─ Bootstrap classes for all classloaders
│  ├─ datadog/trace/bootstrap/
│  │  ├─ instrumentation/
│  │  │  ├─ api/          ─ AgentSpan, AgentTracer, AgentScope APIs
│  │  │  ├─ decorator/    ─ Base decorators (Http, Database, Messaging, etc.)
│  │  │  └─ java/         ─ Java stdlib helpers
│  │  ├─ CallDepthThreadLocalMap ─ Recursion tracking
│  │  └─ ContextStore     ─ Attach data to objects across instrumentations
│  └─ WeakMap, FieldBackedContextStores ─ Internal context storage
├─ agent-builder/          ─ Agent construction and transformation
│  ├─ AgentBuilder        ─ ByteBuddy agent initialization
│  ├─ AgentListener       ─ Transformation callbacks
│  └─ matcher/            ─ Type/method matchers
├─ agent-tooling/          ─ Instrumentation framework
│  ├─ InstrumenterModule  ─ Base module classes (Tracing, Profiling, etc.)
│  ├─ Instrumenter        ─ Type matching interfaces
│  ├─ AdviceTransformation ─ Advice application
│  ├─ bytebuddy/matcher/  ─ Custom ByteBuddy matchers
│  ├─ muzzle/             ─ Instrumentation safety checks
│  └─ log/                ─ Logging infrastructure
├─ instrumentation/        ─ 120+ framework integrations
│  ├─ <framework>/
│  │  └─ <framework>-<version>/
│  │     ├─ src/main/java/
│  │     │  └─ datadog/trace/instrumentation/<framework>/
│  │     │     ├─ *Instrumentation.java
│  │     │     ├─ *Advice.java
│  │     │     ├─ *Decorator.java
│  │     │     └─ Helper classes
│  │     ├─ src/test/groovy/
│  │     └─ build.gradle
│  ├─ akka/               ─ Akka actor and stream instrumentation
│  ├─ apache-httpclient/  ─ Apache HttpClient 4.x and 5.x
│  ├─ aws-java-sdk/       ─ AWS SDK v1 and v2
│  ├─ cassandra/          ─ Cassandra driver instrumentation
│  ├─ couchbase/          ─ Couchbase SDK versions 2.x-4.x
│  ├─ elasticsearch/      ─ Elasticsearch REST/transport clients
│  ├─ grpc/               ─ gRPC client and server
│  ├─ hibernate/          ─ Hibernate ORM versions
│  ├─ http-url-connection/ ─ Java HttpURLConnection
│  ├─ java-concurrent/    ─ Java Executor and ForkJoinPool
│  ├─ jdbc/               ─ JDBC drivers and connection pools
│  ├─ jms/                ─ JMS 1.x and 2.x messaging
│  ├─ kafka/              ─ Kafka clients and streams
│  ├─ lettuce/            ─ Lettuce Redis client
│  ├─ mongo/              ─ MongoDB drivers 3.x and 4.x
│  ├─ netty/              ─ Netty 3.x and 4.x
│  ├─ okhttp/             ─ OkHttp client versions
│  ├─ play/               ─ Play Framework
│  ├─ rabbitmq/           ─ RabbitMQ AMQP client
│  ├─ ratpack/            ─ Ratpack framework
│  ├─ reactor/            ─ Project Reactor
│  ├─ redis/              ─ Jedis and other Redis clients
│  ├─ servlet/            ─ Servlet 2.x-6.x
│  ├─ spark/              ─ Apache Spark and Spark Java web framework
│  ├─ spring/             ─ Spring Framework and Spring Boot
│  └─ vertx/              ─ Eclipse Vert.x
├─ agent-profiling/        ─ Continuous Profiler
│  ├─ ddprof-integration/ ─ ddprof native profiler integration
│  └─ profiling-*         ─ JFR-based profiling components
├─ agent-debugger/         ─ Dynamic Instrumentation
│  └─ debugger-*          ─ Probe injection and snapshot capture
├─ agent-iast/             ─ Interactive Application Security Testing
│  └─ Taint tracking and vulnerability analysis
├─ appsec/                 ─ Application Security (WAF/RASP)
│  └─ WAF engine integration
├─ agent-ci-visibility/    ─ CI Visibility
│  └─ Test instrumentation
└─ agent-jmxfetch/         ─ JMX Metrics
   └─ JMX bean collection
```

**Component Details:**

- agent-bootstrap — Bootstrap components visible to all classloaders
  - instrumentation/api — Core instrumentation APIs (AgentSpan, AgentScope, AgentTracer, propagation).
  - instrumentation/decorator — Base decorator hierarchy (BaseDecorator, ClientDecorator, ServerDecorator, HttpClientDecorator, DatabaseClientDecorator, MessagingClientDecorator, etc.).
  - instrumentation/java — Java stdlib helpers for concurrent, streams, classloaders, etc.
  - CallDepthThreadLocalMap — Tracks call depth to avoid duplicate spans in recursive calls.
  - ContextStore — Attaches state to arbitrary objects across instrumentations.
- agent-builder — ByteBuddy agent construction
  - AgentBuilder configuration, listeners, and transformation pipeline.
  - ClassLoaderMatchers and type exclusions.
- agent-tooling — Instrumentation infrastructure
  - InstrumenterModule hierarchy (Tracing, Profiling, AppSec, Iast, CiVisibility, Usm, ContextTracking).
  - Type matching (ForSingleType, ForKnownTypes, ForTypeHierarchy).
  - Method matching (AdviceTransformation with ElementMatchers and DDElementMatchers).
  - Muzzle safety checks (compile-time verification of instrumentation compatibility).
- instrumentation — Framework-specific integrations
  - Each integration extends InstrumenterModule and implements type/method matching.
  - Advice classes use @Advice.OnMethodEnter/@OnMethodExit to inject tracing logic.
  - Decorators extend base decorators to add framework-specific span tags.
  - Muzzle directives in build.gradle specify safe version ranges.
  - Tests written in Groovy/Spock extending base test frameworks.
- agent-profiling — Continuous Profiler
  - ddprof integration for native CPU/wall profiling.
  - JFR-based profiling for allocation, heap, and exceptions.
- agent-debugger — Dynamic Instrumentation
  - Runtime probe injection, snapshot capture, and upload.
  - Expression evaluation and rate limiting.
- agent-iast — Interactive Application Security Testing
  - Taint tracking (sources, sinks, propagators).
  - Vulnerability analysis (SQL injection, XSS, etc.).
- appsec — Application Security
  - WAF engine integration for request/response analysis.
  - RASP capabilities.
- agent-ci-visibility — CI Visibility
  - Test framework instrumentation (JUnit, TestNG, Spock, etc.).
- agent-jmxfetch — JMX Metrics
  - JMX bean discovery and metric collection.

</details>

## Build & Development

**Quick start:**
- Build: `./gradlew clean assemble` (all platforms)
- Agent JAR: `./gradlew :dd-java-agent:shadowJar` (produces dd-java-agent/build/libs/dd-java-agent-*.jar)
- Unit tests: `./gradlew test`
- Integration tests: `./gradlew :dd-java-agent:instrumentation:<framework>:<framework>-<version>:test`
- Latest dependency tests: `./gradlew :dd-java-agent:instrumentation:<framework>:<framework>-<version>:latestDepTest`

📖 **Load when**: Setting up development environment, running builds, or troubleshooting build issues
- **`BUILDING.md`** — Complete development setup guide (JDK requirements, Docker, container runtime, platform-specific setup, and Gradle commands)

## Creating Integrations

**Quick reference:**
- Location: `dd-java-agent/instrumentation/<framework>/<framework>-<minVersion>/`
- Create `*Instrumentation` class extending `InstrumenterModule.Tracing` (or other target system)
- Implement type matching interface (`ForSingleType`, `ForKnownTypes`, or `ForTypeHierarchy`)
- Override `adviceTransformations()` to apply advice to matched methods using ByteBuddy ElementMatchers
- Create `*Advice` static class with `@Advice.OnMethodEnter` and `@Advice.OnMethodExit` methods
- Create `*Decorator` class extending appropriate base decorator (HttpClientDecorator, DatabaseClientDecorator, etc.)
- Add helper classes to `helperClassNames()` method
- Add muzzle directives to `build.gradle` specifying safe version ranges
- Add instrumentation to `settings.gradle.kts` in alphabetical order
- Tests: Add Spock tests under `src/test/groovy` extending base test frameworks

📖 **Load when**: Creating a new integration or adding instrumentation to an existing library
- **`docs/add_new_instrumentation.md`** — Step-by-step guide to creating integrations, advice transformations, decorators, and testing
- **`docs/how_instrumentations_work.md`** — Deep dive into instrumentation architecture, ByteBuddy advice, type/method matching, helper classes, decorators, context stores, span lifecycle, and testing patterns

## Coding Standards

**Java style:**
- See `.editorconfig` (4-space indent). Types/methods PascalCase; locals camelCase
- Use modern Java syntax (lambdas, streams, var), but avoid features requiring newer runtime types unavailable on older JDKs
- Prefer precise type/method matching over broad matchers
- Add missing `import` statements instead of fully-qualified type names
- Use Spotless for automatic formatting: `./gradlew spotlessApply`
- Run formatting verification: `./gradlew spotlessCheck`

**Groovy style (tests):**
- Follow Spock conventions for test naming and structure
- See `.editorconfig` for import organization

## Performance Guidelines

The tracer runs in-process with customer applications and must have minimal performance impact.

**Critical code paths:**
1. **Bootstrap/Startup Code**: Agent initialization, instrumentation loading, static constructors, configuration loading
2. **Hot Paths**: Span creation/tagging, context propagation, advice execution, decorator methods

**Key patterns:**
- **Minimize allocations**: Reuse objects, avoid boxing, prefer primitive arrays
- **Efficient matching**: Use `ForSingleType`/`ForKnownTypes` over expensive `ForTypeHierarchy` when possible
- **CallDepth tracking**: Use `CallDepthThreadLocalMap` to avoid duplicate spans in recursive calls
- **Context stores**: Pre-declare in `contextStore()` and check for null keys
- **Avoid reflection in advice**: Use ByteBuddy type references, not reflection

## Testing

**Frameworks:** Spock/Groovy (instrumentation tests), JUnit (core tests)
**Test style:** Given-When-Then (Spock), assertions with Hamcrest/AssertJ
**Docker:** Many integration tests require Docker; services managed by Testcontainers
**Filters:** `-PtestJvm=11` to test on specific JVM, `--tests ClassName` to run specific tests

**Testing patterns:**
- Extend `InstrumentationSpecification` for instrumentation tests
- Use base test classes for consistency (HttpClientTest, HttpServerTest, DatabaseClientTest, etc.)
- Add `latestDepTest` configuration to test against latest framework versions
- Muzzle directives verify instrumentation compatibility at build time
- Smoke tests in `dd-smoke-tests/` test real applications with agent attached

## Commit & Pull Request Guidelines

**Commits:**
- Imperative mood; optional scope tag (e.g., `[JDBC]`, `[AppSec]`)
- Reference issues when applicable
- Keep messages concise

**Pull Requests:**
- Follow `.github/pull_request_template.md`
- Title: Brief description starting with infinitive verb (e.g., "Fix span sampling rule parsing")
- Labels: Always add `comp:` or `inst:` label, and `type:` label
- Use `tag: no release note` for internal changes not relevant to users
- Clear description, linked issues, testing notes
- Include tests and docs for changes
- CI: All checks must pass

## Documentation References

**Core docs:**
- `README.md` — Overview, getting started, and links
- `CONTRIBUTING.md` — Contribution process and PR guidelines
- `BUILDING.md` — Dev setup, platform requirements, and build commands
- `docs/releases.md` — Release process and schedule

**Development guides:**
- `docs/add_new_instrumentation.md` — Creating integrations step-by-step
- `docs/how_instrumentations_work.md` — Deep dive into instrumentation architecture
- `docs/add_new_configurations.md` — Adding configuration options
- `docs/how_to_test.md` — Testing strategies and patterns
- `docs/how_to_work_with_gradle.md` — Gradle tips and tricks
- `docs/bootstrap_design_guidelines.md` — Bootstrap classloader design principles

## Security & Configuration

- Do not commit secrets; use environment variables (`DD_*` or `dd.*` system properties)
- Configuration via environment variables, system properties, or dd.properties file
- See [configuration reference](https://docs.datadoghq.com/tracing/trace_collection/library_config/java) for all options

## Glossary

Common acronyms used in this repository:

- **APM** — Application Performance Monitoring
- **AppSec** — Application Security (WAF/RASP)
- **IAST** — Interactive Application Security Testing
- **CI** — Continuous Integration / CI Visibility
- **CP** — Continuous Profiler
- **DBM** — Database Monitoring
- **DI** — Dynamic Instrumentation
- **DSM** — Data Streams Monitoring
- **JFR** — Java Flight Recorder
- **JMX** — Java Management Extensions
- **OT** — OpenTracing
- **OTEL** — OpenTelemetry
- **RCM** — Remote Configuration Management
- **USM** — Universal Service Monitoring
- **WAF** — Web Application Firewall
