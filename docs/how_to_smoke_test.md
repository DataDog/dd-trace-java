# How to Write a Smoke Test

Smoke tests launch a real application in its own JVM, attached to your built `-javaagent`, and assert on what its tracer sends over the agent protocol.
This guide covers the JUnit 5 smoke test framework in `:dd-smoke-tests`.

## When to write a smoke test

A smoke test runs the agent the way a customer runs it: a separate process, the `premain` call, bytecode transformation at class-load time, payloads on the wire, and every instrumentation loaded at once.
An instrumentation test exercises one instrumentation on its own, so it cannot see the failures that only show up in a real application:

- Two instrumentations advising the same class, competing for the same context, or applying in an order that only happens when both are present.
  Each one passes its own test, and together they break.
- Instrumentations interacting across a real stack: context propagation through a framework, a client, a broker and back.
  No single integration's test covers those seams.
- Products interacting, with tracing, AppSec, IAST, profiling and telemetry all active in the same JVM, competing for the same classes and the same start-up budget.

A smoke test is also how you check what an instrumentation does to a running application rather than what spans it emits: that the app still starts, still behaves correctly, and is not degraded by being instrumented.

| Question                                                                                                              | Use                                                                                 |
|-----------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------|
| Do the instrumentation hooks fire, and does the resulting trace look right?                                           | An instrumentation test, see [How to Test With JUnit](./how_to_test_with_junit.md)  |
| Does the instrumentation *behave* correctly in a real application, alongside every other instrumentation and product? | A smoke test                                                                        |
| Does the agent behave correctly *as an agent* (start-up, config, remote config, telemetry)?                           | A smoke test                                                                        |
| Does behaviour match across all Datadog tracer libraries?                                                             | A [system test](https://github.com/DataDog/system-tests)                            |

> [!TIP]
> The two are complements, not alternatives.
> Use an instrumentation test to pin the hooks and the trace shape, and a smoke test to check the instrumentation works in an application where every other instrumentation is loaded too.

## Your first smoke test

A complete smoke test for a small web application serving two endpoints, with one test method per endpoint:

```java
package datadog.smoketest;

import static datadog.smoketest.backend.AgentBackend.testAgent;
import static datadog.smoketest.trace.SpanMatcher.span;
import static datadog.smoketest.trace.TraceMatcher.trace;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class MyServerSmokeTest {
  private static final String APPLICATION_JAR =
      System.getProperty("datadog.smoketest.shadowJar.path");

  @RegisterExtension
  static final SmokeServerApp app =
      SmokeServerApp.named("my-server")
          .jar(APPLICATION_JAR)
          .args("--server.port=${app.httpPort}")
          .backend(testAgent())
          .build();

  @Test
  void tracesASuccessfulRequest() {
    assertEquals(200, app.get("/success"));

    app.traces()
        .waitForTraces(
            trace(
                span()
                    .root()
                    .operationName("servlet.request")
                    .resourceName("GET /success")
                    .type("web")
                    .error(false)
                    .tag("http.status_code", "200"),
                span()
                    .operationName("spring.handler")
                    .resourceName("MyController.success")
                    .childOfPrevious()));
  }

  @Test
  void tracesAFailingRequest() {
    assertEquals(500, app.get("/failure"));

    app.traces()
        .waitForTraces(
            trace(
                span()
                    .root()
                    .operationName("servlet.request")
                    .resourceName("GET /failure")
                    .error(true)
                    .tag("http.status_code", "500"),
                span()
                    .operationName("spring.handler")
                    .resourceName("MyController.failure")
                    .childOfPrevious()));
  }
}
```

Declaring the smoke app as a `static @RegisterExtension` field is what wires it into JUnit.
The framework launches the app and its backend before the class, resets state between test methods, and tears everything down afterward.

The example relies on two behaviors you get by default.
`SmokeServerApp` picks a free port and substitutes it into `${app.httpPort}` at launch, so parallel modules do not collide, and both `app.get(path)` and `app.url()` target it.
The backend is reset before every test method, which is why each test can assert a single trace without filtering out the other endpoint's.

Three system properties matter, and you only supply the last one:

| Property                                 | Set by                                              | Meaning                                                 |
|------------------------------------------|-----------------------------------------------------|---------------------------------------------------------|
| `datadog.smoketest.agent.shadowJar.path` | `dd-smoke-tests/build.gradle` (`subprojects` block) | The agent jar attached as `-javaagent`                  |
| `datadog.smoketest.builddir`             | `dd-smoke-tests/build.gradle` (`subprojects` block) | Module build dir; app logs land in `<builddir>/reports` |
| `datadog.smoketest.shadowJar.path`       | Your module's `build.gradle`                        | The application fixture to launch                       |

Run it with:

```shell
./gradlew :dd-smoke-tests:my-module:test
```

## Choosing an app type

There are two app types, and the choice follows from how the application's lifetime relates to your test methods:

- A server starts and stays up.
  The test drives it: each method sends it work and asserts on what came back.
  The app outlives every method, and the test decides when anything happens.

- A batch or CLI app does its work and exits.
  Its own arguments drive it, not the test.
  By the time the first method runs it may already have emitted everything it will ever emit.

The framework handles three things differently for each type: when the app is ready, whether state is reset between methods, and how you assert the run finished correctly.

### `SmokeServerApp`

For a long-running server:

- Nothing runs until the app's port is open, so the first test method does not race start-up.
- The framework picks a free port and substitutes it into `${app.httpPort}` at launch, so modules run in parallel without colliding.
  Read it back with `httpPort()`, or `url()` for the base URL.
- `app.get(path)` issues a GET request and returns the status code.
  For other verbs, headers or bodies, point your own HTTP client at `app.url()`.
- The owned backend and the captured logs are cleared before each test method, so a method sees only the traces and output its own requests produced.
- The process is asserted alive before and after each method, so a crash fails the method that caused it instead of surfacing later as an unexplained timeout.

```java
@RegisterExtension
static final SmokeServerApp app =
    SmokeServerApp.named("my-server")
        .jar(APPLICATION_JAR)
        .args("--server.port=${app.httpPort}")
        .backend(testAgent())
        .build();
```

### `SmokeCliApp`

For an app that runs to completion:

- There is no port to wait for, so the app is launched and the test body starts immediately.
  Your first assertion is what waits.
- `assertCompletesWithValue(timeout, unit, exitCode)` waits for the process to terminate and checks its exit code.
  Pass a non-zero expected value for apps that are supposed to fail, such as a tool the agent aborts.
- The backend is not reset between methods.
  A batch app often emits everything during start-up, before the first method runs, and a per-method reset would throw those traces away, so they accumulate for the whole class instead.
- There is no per-method liveness check, since the process exiting is the expected outcome.

```java
@RegisterExtension
static final SmokeCliApp app =
    SmokeCliApp.named("my-batch")
        .jar(APPLICATION_JAR)
        .backend(testAgent())
        .build();

@Test
void runsToCompletion() {
  app.traces().waitForTraces(trace(span().root().operationName("Application.main")));
  app.assertCompletesWithValue(30, SECONDS, 0);
}
```

## Configuring the launched app

| Group            | Methods                                                                                                                        |
|------------------|--------------------------------------------------------------------------------------------------------------------------------|
| What to run      | `jar(path)`, `mainClass(Class \| String)`, `mainClass(Class \| String, classpath)`, `args(...)`, `placeholder(name, supplier)` |
| JVM              | `jvmArgs(...)`, `startupTimeoutSeconds(n)`, `debugLogs()`, `skipMemoryTuning()`                                                |
| Environment      | `env(k, v)`, `workingDirectory(dir)`                                                                                           |
| Agent            | `javaAgent(path)`, `noAgent()`                                                                                                 |
| Automatic checks | `skipErrorLogCheck()`, `allowedErrorLogs(...)`, `errorLogFilter(p)`, `skipTelemetryCheck()`                                    |

Either `jar(...)` or `mainClass(...)` is required, as is `backend(...)` (see [Choosing a backend](#choosing-a-backend)).
`build()` fails fast otherwise.

### Placeholders

`placeholder(name, supplier)` defers a value to launch time rather than builder time.
Use it for anything that does not exist while static fields initialize:

```java
.placeholder("rabbit.port", () -> String.valueOf(RABBIT_MQ_CONTAINER.getMappedPort(5672)))
.args("--spring.rabbitmq.port=${rabbit.port}")
```

Occurrences of `${name}` are then substituted in both `args(...)` and `jvmArgs(...)`.

### Extension ordering

Two different orderings are at play, and confusing them is a common way to end up with a `NullPointerException` or a wrong port. 

- Static field initialization is plain Java: initializers run in source order when the class loads, so everything a builder reads while it is being constructed must already exist at that point.
- Extension callback order, meaning whose `beforeAll` runs first, is deterministic but unspecified by JUnit.

Annotate the fields with `@Order` when it matters, for example to start the shared backend before the apps that report to it.

> [!WARNING]
> `@Testcontainers` starts `@Container` fields from its own `beforeAll`, which runs long after static initialisation.
> A mapped container port does not exist while your builder is running, whatever order the fields are declared in.
> That is what `placeholder(...)` is for: it defers the value to launch time.

```java
@Container
private static final RabbitMQContainer RABBIT_MQ_CONTAINER = new RabbitMQContainer(/* ... */);

@Order(1)
@RegisterExtension
static final TestAgentBackend agent = AgentBackend.testAgentBuilder().retainAcrossTests().build();

@Order(2)
@RegisterExtension
static final SmokeServerApp sender = /* ... */;
```

## Choosing a backend

The backend is the agent stand-in the app reports to.
It exposes three capture surfaces, `traces()`, `telemetry()` and `remoteConfig()`, and the same test body works against either implementation.
What separates them is who validates what the tracer sent.

### `testAgent()`

The tracer talks to a real agent, the [dd-apm-test-agent](https://github.com/DataDog/dd-apm-test-agent), so the payload has to be one a real agent accepts.
The agent parses the msgpack itself and rejects what it cannot read, and it runs the trace-invariant checks (`ENABLED_CHECKS`) over everything it receives.
This is the default, and **what every committed test should use**.

`testAgent()` resolves the environment automatically, using the CI agent instance when running on GitLab.
Because the shared CI agent serves every job, traces are scoped by an `X-Datadog-Test-Session-Token`.

### `mockAgent()`

An in-process HTTP test server that needs no Docker environment.
It decodes the payload with our own `Decoder`, has no trace-invariant checks, and silently succeeds on any unimplemented endpoint, so a passing test only shows the tracer is self-consistent.
**Limit it to local prototyping, and switch to `testAgent()` before the test lands.**

### Backend builder

Use `AgentBackend.testAgentBuilder()` when the test agent defaults don't fit:

| Method                            | Purpose                                                                              |
|-----------------------------------|--------------------------------------------------------------------------------------|
| `image(String)`                   | Pin a container image (voids `external`)                                             |
| `external(String host, int port)` | Talk to an already-running agent (voids `image`)                                     |
| `enabledChecks(String...)`        | Replace the default trace-invariant checks                                           |
| `retainAcrossTests()`             | Do not clear between test methods, required when assertions cover app-startup traces |
| `sessionToken(String)`            | Pin the session token instead of generating one                                      |

### Owned vs shared backends

Pass a backend to a single app with `.backend(...)` and that app owns it.
The app starts it, resets it between methods, and closes it.
Register the backend as its own `@RegisterExtension` and it becomes shared instead.
It then drives its own lifecycle and several apps can report to it, which is how you capture one distributed trace spanning two JVMs.

## Asserting traces

`app.traces()` (or `backend.traces()`) returns the query facade:

| Method                                                                           | Purpose                                                              |
|----------------------------------------------------------------------------------|----------------------------------------------------------------------|
| `waitForTraceCount(n)` / `(n, timeoutSeconds)`                                   | Block until at least `n` traces arrived                              |
| `waitForTraces(matchers...)`                                                     | Poll until the received traces match, one matcher per expected trace |
| `waitForTraces(options, matchers...)` / `(timeoutSeconds, options, matchers...)` | Same, with matching options and/or a custom timeout                  |
| `getTraces()`                                                                    | Snapshot of what has arrived, for ad-hoc assertions                  |

```java
app.traces()
    .waitForTraces(
        trace(
            span().root().operationName("servlet.request").resourceName("GET /greeting"),
            span().operationName("spring.handler").childOfPrevious()));
```

### Span matchers

Build spans with `span()`, then chain any of:

| Method                                                       | Matches                                                 |
|--------------------------------------------------------------|---------------------------------------------------------|
| `service(String)`                                            | Service name                                            |
| `operationName(String \| Pattern)`                           | Operation name                                          |
| `resourceName(String \| Pattern \| Predicate<CharSequence>)` | Resource name                                           |
| `type(String)`                                               | Span type (e.g. `web`)                                  |
| `error(boolean)`                                             | Error flag                                              |
| `root()`                                                     | Span has no parent                                      |
| `childOf(long spanId)`                                       | Parent by span id                                       |
| `childOfIndex(int)`                                          | Parent by position in the same trace                    |
| `childOfPrevious()`                                          | Parent is the preceding matcher, chaining a linear tree |
| `tag(name, String \| Matcher<String>)`                       | A `meta` tag                                            |
| `metric(name, Matcher<Number>)`                              | A `metrics` entry                                       |
| `metaStruct(name, Matcher<?>)`                               | A meta-struct entry                                     |

### Matching options

Spans within a trace, and traces within the collection, are matched positionally and count-exact by default.
Relax that with options:

| Option                                          | Effect                                       |
|-------------------------------------------------|----------------------------------------------|
| `SmokeTraceAssertions.UNORDERED`                | Each matcher matches any distinct trace      |
| `SmokeTraceAssertions.IGNORE_ADDITIONAL_TRACES` | Extra received traces are tolerated          |
| `SmokeTraceAssertions.SORT_BY_START_TIME`       | Sort traces by start time first              |
| `TraceMatcher.SORT_BY_START_TIME`               | Sort a trace's spans by start time           |
| `TraceMatcher.SORT_BY_ANCESTRY`                 | Sort a trace's spans parents-before-children |

Options compose, and per-trace options go on `trace(...)`:

```java
app.traces()
    .waitForTraces(
        options -> options.unorder().ignoreAdditionalTraces(),
        trace(SORT_BY_ANCESTRY, span().root().operationName("servlet.request"), /* ... */));
```

> [!TIP]
> Prefer `SORT_BY_ANCESTRY` over `SORT_BY_START_TIME` for a deep parent to child chain.
> Spans that start within the same tick race each other, which makes start-time order unstable across runs.

> [!IMPORTANT]
> These matchers are `datadog.smoketest.trace.*`.
> Instrumentation tests use a different API with the same class names, `datadog.trace.agent.test.assertions.*`, because the smoke variant matches the decoded wire payload rather than in-process spans.
> Check your imports.

## Asserting telemetry

`app.backend().telemetry()` (or `backend.telemetry()`) returns the query facade:

| Method                                                   | Purpose                                                                      |
|----------------------------------------------------------|------------------------------------------------------------------------------|
| `getMessages()`                                          | Raw messages, one per intake request                                         |
| `getFlatMessages()`                                      | Individual events, expanding each `message-batch` into its `payload` entries |
| `waitForCount(n)` / `(n, timeoutSeconds)`                | Block until `n` raw messages arrived                                         |
| `waitForFlat(predicate)` / `(predicate, timeoutSeconds)` | Block until a flattened event matches                                        |

Use `getFlatMessages()` and `waitForFlat(...)` for almost everything.
The tracer batches events, so an `app-started` you are looking for usually arrives nested inside a `message-batch`.

One telemetry assertion runs at the first `afterEach` without you writing it: `assertTelemetryReceived()` checks that at least one message reached the backend.
It only asserts that telemetry is flowing, not that a specific event arrived.
Turn it off with `skipTelemetryCheck()` on the app builder, for apps that run with telemetry disabled.

## Asserting remote config

`app.backend().remoteConfig()` (or `backend.remoteConfig()`) returns the query facade:

| Method                                                      | Purpose                                                 |
|-------------------------------------------------------------|---------------------------------------------------------|
| `setConfig(path, config)`                                   | Serve a config on the tracer's next `/v0.7/config` poll |
| `requests()`                                                | Poll requests captured so far                           |
| `waitForRequest(predicate)` / `(predicate, timeoutSeconds)` | Block until a poll matches, and return it               |
| `RemoteConfig.products(request)` (static)                   | Products a poll subscribes to                           |
| `RemoteConfig.capabilities(request)` (static)               | Capability bitmask a poll advertises                    |

## Asserting application logs

| Method                                                | Purpose                                                                                |
|-------------------------------------------------------|----------------------------------------------------------------------------------------|
| `waitForLogLine(predicate)` / `(predicate, duration)` | Block until a captured stdout/stderr line matches                                      |
| `assertNoErrorLogs()`                                 | Assert the captured log holds no error line; auto-invoked at teardown unless opted out |

`waitForLogLine` returns `false` on timeout rather than throwing, so wrap it in an assertion:

```java
assertTrue(
    app.waitForLogLine(line -> line.contains("REQUEST GET /ping")),
    "app logged the request");
```

Already-collected lines are scanned first, without waiting; the timeout only applies once the captured output runs dry.
Captured lines are cleared before each test method (`SmokeServerApp`), so a predicate only sees output the current test produced.

`assertNoErrorLogs()` runs at `afterAll` without you writing it, over the whole log captured since launch.
Call it explicitly to fail earlier, at the point in the test where the log should already be clean.

There are three ways to keep an expected error message from failing the build:

| Option                      | Use for                                                           |
|-----------------------------|-------------------------------------------------------------------|
| `allowedErrorLogs("...")`   | One test's known-noisy lines, matched by substring                |
| `errorLogFilter(predicate)` | Full control over what counts as an error; replaces the allowlist |
| `skipErrorLogCheck()`       | Tests that are *about* error cases                                |

Repository-wide known-flaky messages are excluded centrally in [`KnownLogExclusion`](../dd-smoke-tests/src/main/java/datadog/smoketest/KnownLogExclusion.java).

> [!IMPORTANT]
> Add to `KnownLogExclusion` only for issues affecting every suite; use `allowedErrorLogs(...)` for one test.

## Adding a new smoke-test module

1. Register it in `settings.gradle.kts` (the `:dd-smoke-tests:*` list).
2. Create `dd-smoke-tests/<name>/build.gradle` with the `application` and `com.gradleup.shadow` plugins, `testImplementation project(':dd-smoke-tests')`, and a `Test` task that publishes the app fixture:

   ```groovy
   tasks.withType(Test).configureEach {
     dependsOn 'shadowJar'
     def shadowJarTask = tasks.named('shadowJar', ShadowJar)
     jvmArgumentProviders.add(new CommandLineArgumentProvider() {
         @Override
         Iterable<String> asArguments() {
           return shadowJarTask.map { ["-Ddatadog.smoketest.shadowJar.path=${it.archiveFile.get()}"] }.get()
         }
       })
   }
   ```

3. Write the application fixture under `src/main/java`, and the test under `src/test/java`.
4. Refresh the module's `gradle.lockfile`.

The parent `dd-smoke-tests/build.gradle` already gives every subproject the agent jar, the build directory and the `:dd-smoke-tests` framework, so do not re-declare those.

## Running and debugging

```shell
./gradlew :dd-smoke-tests:<name>:test                           # the module
./gradlew :dd-smoke-tests:<name>:test --tests 'MyAppSmokeTest'  # one class
./gradlew :dd-smoke-tests:<name>:test -PtestJvm=11              # on another JVM
```

The launched app's stdout and stderr are written to `<name>/build/reports/smoke-app.<name>.<UTC-timestamp>.log`.
Add `.debugLogs()` to the builder to run the app with `debug` tracer and application log levels.
