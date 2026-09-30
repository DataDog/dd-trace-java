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
./gradlew :dd-java-agent:observerJar
# Result: dd-java-agent/build/observer/dd-observer-agent.jar
```

The rewrite replaces the output atomically, so a JVM still running from the previous
jar is not affected. Copy the jar to a stable path before a long run anyway.

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

The observer does not import the subject's DD/OTEL properties, environment,
config-file selection or ambient propagation headers. JVM and CI platform facts are
shared. LOCAL and FLEET stable-config sources stay empty so the observer never opens
host `/etc/datadog-agent` files.

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
OBSERVER="$PWD/dd-java-agent/build/observer/dd-observer-agent.jar"
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

The worker carrier is a bounded, versioned, length-delimited UTF-8 envelope in
Base64. It is not encryption. Inherited namespaced environment is never serialized
into child arguments. Sensitive generated keys, identified by stock metadata, are not
promoted from environment or file sources into JVM properties. Explicit caller JVM
properties keep their role, so do not put secrets on command lines. The optional
`tracing.observer.log.directory` launch input writes separate observer log files.

## How isolation works

The rewriter runs offline on the stock jar. It fails if any known stock seam changes.

- Classes, resources, service files and indexes move under `datadog.trace.observer`.
  This covers tracer globals, shaded dependencies, Byte Buddy and Gradle
  services/resources.
- The field-backed context protocol is renamed: `__datadogObserverContext$` fields and
  `$get$__datadogObserverContext$` / `$put$__datadogObserverContext$` methods,
  including dynamic names. Field injection stays enabled.
- `System` property, environment and `Boolean.getBoolean` calls go through a private
  source view.
- A few literals look like packages but are external names. They keep their stock
  spelling: the default agent and DogStatsD socket paths, the
  `.inject.datadog.attribute.enabled` config suffix, the logs `datadog.product:` tag,
  DogStatsD client and tracer health metric names, and OTLP attribute and scope
  names. Internal context and request attribute keys are still relocated so they do
  not collide with the subject tracer.
- The JUnit 5 and Spock advice only open observer spans for the synchronous outer
  Gradle engine launch. Nested launchers are ignored.

The modifiable-config convention recognizes the attached observer without path or
hash metadata.

Three stock fixes are part of this work:

- `TypeFactory` resolves the current transform target from the supplied bytes, not a
  cached classpath description. Without this, the subject transformer removed an
  interface the observer had injected. This was reproduced on Mockito's
  `DetachedThreadLocal` and aborted a whole retransformation batch. The target
  description is reused for the transform and reset afterwards. Other shared caches
  are unchanged. This adds one parse per transformed target and has not been
  benchmarked.
- `UnknownCIInfo` accepts `.git` as a file when looking for the repository root, as in
  linked worktrees and submodules. Before, local runs from a worktree had no
  repository, branch or commit tags.
- `LineCoverageStore.Factory` loads the per-test coverage recording classes up front.
  JaCoCo probes also land in the tracer under test's `defineClass` hook. Loading a
  class lazily while recording ran that hook, which recorded again until a
  `StackOverflowError`. The JVM then printed
  `java.lang.instrument ASSERTION FAILED ... transform method call failed` and loaded
  the class untransformed.

## Tests

The rewriter, runtime and configuration sources have `buildSrc` tests. The artifact
tests need a built observer and stock jar:

```sh
./gradlew :dd-java-agent:observerJar
./gradlew -p buildSrc test \
  --tests 'datadog.gradle.plugin.observer.*' --tests 'datadog.trace.observer.*' \
  -PrunBuildSrcTests \
  -PobserverTestArtifact="$PWD/dd-java-agent/build/observer/dd-observer-agent.jar" \
  -PobserverTestStock="$(ls "$PWD"/dd-java-agent/build/libs/dd-java-agent-*.jar)"
```

The `TypeFactory`, `UnknownCIInfo` and `LineCoverageStore` changes are covered by the
regular module tests. End-to-end runs against a local mock intake were done by hand
and are not part of the repository.

## Limitations

- Target: the repository's Gradle 9.8 daemon with the stock Gradle 8.10+ hook, the
  JUnit Jupiter and Spock outer lifecycle, and the subject instrumentation harness.
- Stock capabilities are reported. Optional capabilities these tests do not select are
  not proven compatible. No new product features were added.
- Per-test code coverage and coverage report upload work. Failed Test Replay, test
  skipping, Auto Test Retries, Early Flake Detection and Test Management have not been
  verified with the observer.
- Every Gradle build opens its own session, including `buildSrc`, `build-logic` and
  Kotlin DSL accessor builds. Those sessions run no tests but still fetch settings.
- Unverified: arbitrary asynchronous engines, other runners, Maven, launcher injection
  and generic child processes. `JavaExec`, TestKit and smoke-test subprocesses are not
  injected automatically.
- Unsupported: configuration cache, daemon reuse, dynamic attach, AOT/CDS and security
  manager environments.
- The external-literal list is maintained by hand. A new stock string that looks like
  a `datadog.` package but names something external will be relocated until it is
  added.
