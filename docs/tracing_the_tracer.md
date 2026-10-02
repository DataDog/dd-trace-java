# Tracing the tracer: experimental namespace-isolated agent

The observer lets us trace this repository's own test runs with CI Visibility while
the tracer under test runs in the same JVMs. It is the stock Java agent relocated
into a private namespace. It keeps the full instrumenter catalog, capability
reporting and product configuration. Stock configuration decides what runs.

Having the full catalog does not mean every integration or product can coexist with
the tracer under test. See [Limitations](#limitations).

## Build

The observer is a developer-only artifact. It is not part of `assemble`, publication
or ordinary test runs.

```sh
./gradlew :dd-java-agent:observer:observerJar
# Result: dd-java-agent/observer/build/libs/dd-observer-agent.jar
```

The rewrite is reproducible: the same stock jar always gives the same observer jar. It
replaces the output atomically, so a JVM still running from the previous jar is not
affected. Copy the jar to a stable path before a long run anyway.

Ordinary repository tests have no observer dependency or configuration.
`-PtraceTracer=true` only tracks the attached observer jar and configuration as test
inputs. It does not inject an agent or build the jar.

## Injection

Use ordinary premain. No role, hash or custom argument is required:

```sh
java -javaagent:/absolute/path/dd-observer-agent.jar -jar application.jar
java -javaagent:/absolute/path/dd-observer-agent.jar=dd.service=observer -jar application.jar
```

Normal injection uses stock defaults and can start stock transports. Configure
destinations and products for your run.

## Configuration

Use full stock configuration names in the private namespace:

| Input | Observer source |
| --- | --- |
| `-Dtracing.observer.config.dd.service=observer` | JVM property `dd.service` |
| `TRACING_OBSERVER_CONFIG_DD_SERVICE=observer` | Environment `DD_SERVICE` |
| `TRACING_OBSERVER_CONFIG_DD_API_KEY_FILE=/your/key-file` | Environment credential-file selector |
| `-Dtracing.observer.config.dd.trace.config=/your/observer.properties` | Stock properties-file selector |
| `TRACING_OBSERVER_CONFIG_OTEL_SERVICE_NAME=observer` | Stock OTEL environment source |

Stock precedence, aliases, parsing, defaults, collection flags, caller overrides and
API-key property exclusion all apply. Credential files are reread, not snapshotted.
There is no allowlist for sites, endpoints, credentials, writers, products or OTEL.
Stock `Agent.configureCiVisibility` supplies the agent jar URI and CI defaults.
Ordinary properties files cannot activate early bootstrap product gates, as in stock.

The observer has two defaults of its own:

- It turns off the `junit-4`, `testng`, `karate`, `scalatest`, `weaver` and `cucumber`
  integrations. In this repository those frameworks only run as fixtures inside JUnit
  Platform tests, so observing them would report the fixtures as our tests. In Karate
  1.0 it also broke the fixtures.
- It limits per-test code coverage to `datadog.*:com.datadog.*`
  (`civisibility.code.coverage.includes`). This repository has more top-level packages
  than the stock root-package limit (50), so stock would infer no packages and cover
  everything. That instrumented JDK classes and the test-only instrumentation classes
  the tracer under test rewrites, and broke test workers.

The defaults live in the local stable-config source, which has the lowest precedence.
Any explicit setting overrides them: a property, an environment variable or a
properties file, under any stock spelling such as `trace.<name>.enabled`.

The observer does not import the subject's DD/OTEL properties, environment,
config-file selection or ambient propagation headers. JVM and CI platform facts are
shared. The stable-config sources never open host `/etc/datadog-agent` files: the
fleet source is empty and the local source only carries the defaults above.

The implicit logger resource is `observer-simplelogger.properties`. Explicit logger
resources use ordinary logger keys. Logger and Byte Buddy keys are relocated. Their
values are not.

These are source-isolation boundaries. They do not protect against same-process
reflection.

## Gradle daemon injection

Attach the observer to the Gradle daemon, not only the wrapper JVM. Gradle applies
`-D` entries from `org.gradle.jvmargs` after premain, so inherited namespaced
environment variables are the simplest way to configure it.

Example for running against your own staging account:

```sh
: "${STAGING_API_KEY_FILE:?Set STAGING_API_KEY_FILE to your staging key file}"
OBSERVER="$PWD/dd-java-agent/observer/build/libs/dd-observer-agent.jar"
TRACING_OBSERVER_CONFIG_DD_CIVISIBILITY_ENABLED=true \
TRACING_OBSERVER_CONFIG_DD_TRACE_ENABLED=false \
TRACING_OBSERVER_CONFIG_DD_CIVISIBILITY_AGENTLESS_ENABLED=true \
TRACING_OBSERVER_CONFIG_DD_SITE=datad0g.com \
TRACING_OBSERVER_CONFIG_DD_API_KEY_FILE="$STAGING_API_KEY_FILE" \
TRACING_OBSERVER_CONFIG_DD_SERVICE=tracing-the-tracer \
./gradlew --no-daemon --no-configuration-cache --no-scan \
  "-Dorg.gradle.jvmargs=-XX:MaxMetaspaceSize=1g -javaagent:$OBSERVER" \
  -PtraceTracer=true \
  :dd-java-agent:instrumentation-testing:test --tests AgentTestRunnerTest
```

The stock Gradle integration owns sessions, `Test` task modules and worker injection.
Workers receive the generated module settings privately. The subject's
`traceparent`, `tracestate`, baggage and Datadog headers cannot classify an observer
worker. Debug ports, extra JVM args, project-property substitution and JaCoCo
behavior stay stock. Only generated settings are marked, substituted by the stock
argument provider, and then encoded at the worker boundary.

The worker carrier is a bounded, sorted list of NUL-separated UTF-8 entries in
Base64, passed as `tracing.observer.child.v2`. It is not encryption. Inherited namespaced environment is never serialized
into child arguments. Sensitive generated keys, identified by stock metadata, are not
promoted from environment or file sources into JVM properties. Explicit caller JVM
properties keep their role, so do not put secrets on command lines. The optional
`tracing.observer.log.directory` property or `TRACING_OBSERVER_LOG_DIRECTORY`
environment variable writes separate observer log files.

## How isolation works

The rewriter runs offline on the stock jar. Every stock method it depends on is listed
in one table in `ObserverAgentRewriter`, with how often its patch must apply. Renamed
Gradle service and extension names are counted the same way. The rewrite fails if any
count changes. `:dd-java-agent:check` builds the observer and runs its tests, so a
stock change that breaks a seam fails CI instead of the next observed run.

- Classes, resources, service files and indexes move under `datadog.trace.observer`.
  This covers tracer globals, shaded dependencies, Byte Buddy and Gradle
  services/resources. The jar index is built and checked with the stock index
  generator and reader.
- The field-backed context protocol is renamed: `__datadogObserverContext$` fields and
  `$get$__datadogObserverContext$` / `$put$__datadogObserverContext$` methods,
  including dynamic names. Field injection stays enabled.
- `System` property, environment and `Boolean.getBoolean` calls go through a private
  source view.
- String constants only move when they name something under a package root that
  exists in the stock jar: `datadog.<root>`, `com.datadog.<root>` or `net.bytebuddy`.
  Everything else keeps its stock spelling, such as socket paths, metric and OTLP
  names, JNDI names, bare `datadog.` prefixes and request attribute keys. The runtime
  uses the same rule for logger and Byte Buddy property keys.
- `datadog.compiler` is never relocated. The javac plugin writes its annotation types
  into compiled classes, so both tracers must read the same types.
- The JUnit 5 and Spock advice only open observer spans for the synchronous outer
  Gradle engine launch. Nested launchers are ignored. Other test frameworks are off by
  default, see [Configuration](#configuration). If a worker runs an engine but never
  sees Gradle's launch, it prints a warning at exit, because no tests were reported.

Some patches are permanent, because they are what makes the copy private: relocation,
the context protocol rename, the `System` redirection and the worker carrier. Others
could become small stock options and be removed from the rewriter:

- Ignoring nested JUnit Platform launchers.
- Not reading host stable-config files.
- A configurable Gradle service and extension name.
- A numeric IPC host for workers (`getHostAddress`), which looks useful in stock too.

The modifiable-config convention recognizes the attached observer without path or
hash metadata.

Four stock fixes are part of this work:

- `TypeFactory` resolves the current transform target from the supplied bytes when the
  type cache already has an entry for it. That entry can predate changes from earlier
  transformers. Without this, the subject transformer removed an interface the
  observer had injected. This was reproduced on Mockito's `DetachedThreadLocal` and
  aborted a whole retransformation batch. On a cache miss, the target is parsed and
  shared as before. The extra parse only happens on a cache hit for the target, for
  example after a supertype lookup or on retransformation. It has not been
  benchmarked.
- `UnknownCIInfo` accepts `.git` as a file when looking for the repository root, as in
  linked worktrees and submodules. Before, local runs from a worktree had no
  repository, branch or commit tags.
- `LineCoverageStore.Factory` loads the per-test coverage recording classes up front.
  JaCoCo probes also land in the tracer under test's `defineClass` hook. Loading a
  class lazily while recording ran that hook, which recorded again until a
  `StackOverflowError`. The JVM then printed
  `java.lang.instrument ASSERTION FAILED ... transform method call failed` and loaded
  the class untransformed. A test fails if recording loads a class again.
- An empty code coverage include or exclude entry now matches nothing. A parent that
  infers no root packages propagates an empty include list to its workers. Each worker
  parsed it as one empty prefix, which matched every class, including the JDK's
  generated reflection accessors. Without JaCoCo, file-level coverage then crashed the
  worker before any test ran.

## Tests

The rewriter and runtime live in `:dd-java-agent:observer`. Its tests build the stock
and observer jars first, then compare stock and relocated configuration on the real
artifacts and check that rewriting in-process gives the same jar:

```sh
./gradlew :dd-java-agent:observer:test
```

Only the attach detection used by the Gradle conventions stays in `buildSrc`.

The `TypeFactory`, `UnknownCIInfo` and `LineCoverageStore` changes are covered by the
regular module tests. End-to-end runs against a local mock intake were done by hand
and are not part of the repository.

## Limitations

- Target: the repository's Gradle 9.8 daemon with the stock Gradle 8.10+ hook, the
  JUnit Jupiter and Spock outer lifecycle, and the subject instrumentation harness.
- Stock capabilities are reported. Optional capabilities these tests do not select are
  not proven compatible. No new product features were added.
- Per-test code coverage and coverage report upload were checked on a sample of
  modules: `junit-5.3`, `junit-4.10`, `instrumentation-testing`, `java-concurrent-1.8`,
  `okhttp-3.0` and `dd-trace-core`. They work on the default test JVM, where coverage
  uses JaCoCo, and with `-PtestJvm`, where the build turns JaCoCo off and coverage is
  file-level. Other modules are not proven.
- Failed Test Replay, test skipping, Auto Test Retries, Early Flake Detection and Test
  Management have not been verified with the observer.
- Every Gradle build opens its own session, including `buildSrc`, `build-logic` and
  Kotlin DSL accessor builds. Those sessions run no tests but still fetch settings.
- Unverified: arbitrary asynchronous engines, other runners, Maven, launcher injection
  and generic child processes. `JavaExec`, TestKit and smoke-test subprocesses are not
  injected automatically.
- Unsupported: configuration cache, daemon reuse, dynamic attach, AOT/CDS and security
  manager environments.
- Request attribute keys such as `datadog.span.dispatch` keep their stock spelling. If
  both tracers ran the same server tracing integrations in one JVM, they would share
  those attributes. The CI setup above runs the observer with tracing off.
- The outer-engine check matches Gradle's internal JUnit Platform test processor. If a
  Gradle upgrade renames it, workers report no tests and print a warning.
