# How to Test With JUnit Guide

This guide covers the JUnit 5 testing utilities for writing instrumentation and unit tests.

## Parameterized tests

JUnit 5 supports running the same test with different inputs via [`@ParameterizedTest`](https://docs.junit.org/5.14.3/writing-tests/parameterized-classes-and-tests.html#writing-tests-parameterized-classes-and-tests) combined with an argument source.
On top of the standard sources, [`@TableTest`](https://tabletest.org/) can be used for readable tabular data as argument source.

### [`@ValueSource`](https://docs.junit.org/5.14.3/writing-tests/parameterized-classes-and-tests.html#tests-sources-ValueSource)

Single-parameter tests with literal values (`String`, `int`, `long`, `double`, `boolean`, `Class`):

```java
@ParameterizedTest
@ValueSource(strings = {"RANDOM", "SEQUENTIAL", "SECURE_RANDOM"})
void generateIdWithStrategy(String strategyName) {
    // strategyName takes each value in turn
}
```

### [`@NullSource` / `@EmptySource` / `@NullAndEmptySource`](https://docs.junit.org/5.14.3/writing-tests/parameterized-classes-and-tests.html#tests-sources-null-and-empty)

Inject `null`, empty, or both as a single argument.
Often combined with `@ValueSource`:

```java
@ParameterizedTest
@NullSource
@ValueSource(strings = {"", "-1", "18446744073709551616"})
void failOnIllegalString(String stringId) {
    assertThrows(NumberFormatException.class, () -> DDSpanId.from(stringId));
}
```

### [`@EnumSource`](https://docs.junit.org/5.14.3/writing-tests/parameterized-classes-and-tests.html#tests-sources-EnumSource)

Run once per enum constant, optionally filtering with `names` / `mode`:

```java
@ParameterizedTest
@EnumSource(value = PrioritySampling.class, names = {"SAMPLER_KEEP", "USER_KEEP"})
void keepSampledSpans(PrioritySampling priority) { /* ... */ }
```

### [`@CsvSource`](https://docs.junit.org/5.14.3/writing-tests/parameterized-classes-and-tests.html#tests-sources-CsvSource) / [`@CsvFileSource`](https://docs.junit.org/5.14.3/writing-tests/parameterized-classes-and-tests.html#tests-sources-CsvFileSource)

Multiple arguments as inline CSV rows (or an external file).
**Prefer [`@TableTest`](#tabletest) instead**, it provides the same tabular layout with better readability and type-conversion support.

### [`@MethodSource`](https://docs.junit.org/5.14.3/writing-tests/parameterized-classes-and-tests.html#tests-sources-MethodSource)

A static factory method returning `Stream<Arguments>`.
Use when arguments cannot be expressed as literals (objects, builders, mocks):

```java
@ParameterizedTest
@MethodSource("spansProvider")
void testSpanLink(DDSpan span, SpanLink expected) { /* ... */ }

static Stream<Arguments> spansProvider() {
    return Stream.of(
        arguments(buildSpan("op1"), SpanLink.from(someContext())),
        arguments(buildSpan("op2"), SpanLink.from(otherContext())));
}
```

Convention: name the provider `<testMethodName>Arguments` when possible.

### [`@ArgumentsSource`](https://docs.junit.org/5.14.3/writing-tests/parameterized-classes-and-tests.html#tests-sources-ArgumentsSource)

A reusable custom `ArgumentsProvider` implementation.
Use when the same argument set is shared across multiple tests or classes.

### [`@TableTest`](https://tabletest.org/)

A markdown-like table where columns are aligned with `|`, the first row is the header, and one column is conventionally named `scenario` for readable test names.

```java
@TableTest({
    "Scenario          | Identifier             | Expected Identifier",
    "zero              | '0'                    | 0                  ",
    "one               | '1'                    | 1                  ",
    "max               | '18446744073709551615' | DDSpanId.MAX       ",
    "long max          | '9223372036854775807'  | Long.MAX_VALUE     ",
    "long max plus one | '9223372036854775808'  | Long.MIN_VALUE     "
})
void convertIdsFromToString(String id, long expectedId) { /* ... */ }
```

Rules:

- Include a header row with parameter names; `Scenario` is **not** a method parameter and is consumed by the runner for display only.
- Use `|` as delimiter and align columns with spaces for readability.
- Single quote strings that would otherwise be parsed as collections (`'[]'`, `'{}'`), or contain the delimiter (`'a|b'`).
- Blank cell means `null` (for non-primitives); `''` means empty string.
- Collection literals: array/list `[a, b]`, set `{a, b}`, map `[k: v]`.

If you would like to have a custom test name, use the `name` parameter and include the `index` placeholder or any other column name:
```java
@TableTest({ ... })
@ParameterizedTest(name = "convert ids from/to String [{index}]")
```

#### Unparsable constants with `@TypeConverter`

When a cell references a symbolic constant like `Long.MAX_VALUE` or `DDSpanId.MAX`, declare a `@TypeConverter` and register it on the test class with `@TypeConverterSources`.
Prefer a shared class so converters are reused across tests:

```java
// utils/junit-utils - shared across modules
public final class TableTestTypeConverters {
    @TypeConverter
    public static long toLong(String value) {
        switch (value.trim()) {
            case "Long.MAX_VALUE": return Long.MAX_VALUE;
            case "Long.MIN_VALUE": return Long.MIN_VALUE;
            default: return Long.decode(value.trim());
        }
    }
}

// Module-specific extension
public final class DDTraceApiTableTestConverters {
    @TypeConverter
    public static long toLong(String value) {
        switch (value.trim()) {
            case "DDSpanId.MAX":  return DDSpanId.MAX;
            case "DDSpanId.ZERO": return DDSpanId.ZERO;
            default: return TableTestTypeConverters.toLong(value);
        }
    }
}

@TypeConverterSources(DDTraceApiTableTestConverters.class)
class DDSpanIdTest { /* ... */ }
```

#### Combining `@TableTest` with `@MethodSource`

Both annotations can coexist on the same `@ParameterizedTest`: keep tabular cases in `@TableTest` and move only the non-tabular ones (complex builders, mocks) to `@MethodSource`.

### When to use which

| Use                                      | Prefer                                    |
|------------------------------------------|-------------------------------------------|
| One primitive/string parameter           | `@ValueSource` (+ `@NullSource` if needed)|
| Enum values                              | `@EnumSource`                             |
| Multiple primitive/string parameters     | `@TableTest`                              |
| Cases include symbolic constants         | `@TableTest` + `@TypeConverter`           |
| Arguments need builders/mocks/objects    | `@MethodSource`                           |
| Mostly tabular, a few complex cases      | `@TableTest` + `@MethodSource` combined   |
| Reusable provider across classes         | `@ArgumentsSource`                        |

Rule of thumb: reach for `@TableTest` by default for multi-column data - it reads like a spec and keeps cases aligned.
Fall back to `@MethodSource` only when values cannot be expressed as strings.

## Config injection with `@WithConfig`

`@WithConfig` declares configuration overrides for tests.
It injects system properties (`dd.` prefix) or environment variables (`DD_` prefix) and rebuilds the `Config` singleton before each test.

### Class-level config

Applies to all tests in the class:

```java
@WithConfig(key = "service", value = "my-service")
@WithConfig(key = "trace.analytics.enabled", value = "true")
class MyTest extends DDJavaSpecification {
    @Test
    void test() {
        // dd.service=my-service and dd.trace.analytics.enabled=true are set
    }
}
```

### Method-level config

Applies to a single test method, in addition to class-level config:

```java
@WithConfig(key = "service", value = "my-service")
class MyTest extends DDJavaSpecification {
    @Test
    @WithConfig(key = "trace.resolver.enabled", value = "false")
    void testWithResolverDisabled() {
        // dd.service=my-service AND dd.trace.resolver.enabled=false
    }

    @Test
    void testWithDefaults() {
        // only dd.service=my-service
    }
}
```

### Environment variables

Use `env = true` to set an environment variable instead of a system property:

```java
@WithConfig(key = "AGENT_HOST", value = "localhost", env = true)
```

### Raw keys (no auto-prefix)

Use `addPrefix = false` to skip the automatic `dd.`/`DD_` prefix:

```java
@WithConfig(key = "OTEL_SERVICE_NAME", value = "test", env = true, addPrefix = false)
```

### Config with constant references

Annotation values accept compile-time constants:

```java
@WithConfig(key = TracerConfig.TRACE_RESOLVER_ENABLED, value = "false")
```

### Inheritance

`@WithConfig` on a superclass applies to all subclasses.
When a subclass adds its own `@WithConfig`, both the parent's and the subclass's configs are applied (parent first, subclass second).
This allows base classes to set shared config while subclasses add specifics:

```java
@WithConfig(key = "integration.opentelemetry.experimental.enabled", value = "true")
abstract class AbstractOtelTest extends AbstractInstrumentationTest { }

@WithConfig(key = "trace.propagation.style", value = "b3multi")
class B3MultiTest extends AbstractOtelTest {
    // Both configs are active
}
```

### Composed annotations

Bundle multiple configs into a reusable annotation:

```java
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@WithConfig(key = "iast.enabled", value = "true")
@WithConfig(key = "iast.detection.mode", value = "FULL")
@WithConfig(key = "iast.redaction.enabled", value = "false")
public @interface IastFullDetection {}
```

Then reuse across test classes:

```java
@IastFullDetection
class IastTagTest extends DDJavaSpecification { }

@IastFullDetection
class IastReporterTest extends DDJavaSpecification { }
```

### Imperative config injection

For dynamic values that can't be expressed in annotations, use the static methods directly:

```java
@Test
void testDynamic() {
    String port = startServer();
    WithConfigExtension.injectSysConfig("trace.agent.port", port);
    // ...
}
```

### Lifecycle

Config is rebuilt from a clean slate before each test:

1. **`beforeAll`**: class-level `@WithConfig` applied + config rebuilt (available for `@BeforeAll` methods)
2. **`beforeEach`**: properties restored, class + method `@WithConfig` applied, config rebuilt
3. **`afterEach`**: env vars cleared, properties restored, config rebuilt

This means each test starts with a clean config, and method-level `@WithConfig` doesn't leak between tests.

## Instrumentation tests with `AbstractInstrumentationTest`

`AbstractInstrumentationTest` is the JUnit 5 base class for instrumentation tests.
It installs the agent once per test class, creates a shared tracer and writer, and provides trace assertion helpers.

### Lifecycle

| Phase                     | Scope          | What happens                                      |
|---------------------------|----------------|---------------------------------------------------|
| `@BeforeAll initAll()`    | Once per class | Creates tracer + writer, installs ByteBuddy agent |
| `@BeforeEach init()`      | Per test       | Flushes tracer, resets writer                     |
| `@AfterEach tearDown()`   | Per test       | Flushes tracer                                    |
| `@AfterAll tearDownAll()` | Once per class | Closes tracer, removes agent transformer          |

### Available fields

- `tracer`: the DD `TracerAPI` instance (shared across tests in the class)
- `writer`: the `ListWriter` that captures traces written by the tracer

### Configuring the tracer

The tracer can be configured at class level using the `testConfig` builder.
Call it from a static initializer (runs before `@BeforeAll`):

```java
class MyTest extends AbstractInstrumentationTest {
    static {
        testConfig.idGenerationStrategy("RANDOM").strictTraceWrites(false);
    }
}
```

Available settings:

| Method                         | Default        | Description                          |
|--------------------------------|----------------|--------------------------------------|
| `idGenerationStrategy(String)` | `"SEQUENTIAL"` | Span ID generation strategy          |
| `strictTraceWrites(boolean)`   | `true`         | Enable strict trace write validation |

### Basic test

```java
class HttpInstrumentationTest extends AbstractInstrumentationTest {
    @Test
    void testHttpRequest() {
        // exercise the instrumented code
        makeHttpRequest("http://example.com/api");

        // assert the trace structure
        assertTraces(
            trace(
                span().root().operationName("http.request").resourceName("GET /api")));
    }
}
```

## Trace assertion API

The assertion API verifies trace structure using a fluent builder pattern.
Import the static factories:

```java
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.*;
import static datadog.trace.agent.test.assertions.Matchers.*;
```

### Asserting traces

`assertTraces` waits for traces to arrive (20s timeout), then verifies the structure:

```java
// Single trace with 2 spans
assertTraces(
    trace(
        span().root().operationName("parent"),
        span().childOfPrevious().operationName("child")));

// Multiple traces
assertTraces(
    trace(span().root().operationName("trace-1")),
    trace(span().root().operationName("trace-2")));
```

### Trace options

```java
import static datadog.trace.agent.test.assertions.TraceAssertions.*;

// Ignore extra traces beyond the expected ones
assertTraces(IGNORE_ADDITIONAL_TRACES,
    trace(span().root().operationName("expected")));

// Sort traces by start time before assertion
assertTraces(SORT_BY_START_TIME,
    trace(span().root().operationName("first")),
    trace(span().root().operationName("second")));
```

### Span matching

```java
span()
    // Identity
    .root()                                   // root span (parent ID = 0)
    .childOfPrevious()                        // child of previous span in trace
    .childOf(parentSpanId)                    // child of specific span

    // Properties
    .operationName("http.request")            // exact match
    .operationName(Pattern.compile("http.*")) // regex match
    .resourceName("GET /api")                 // exact match
    .serviceName("my-service")                // exact match
    .type("web")                              // span type

    // Error
    .error()                                  // expects error = true
    .error(false)                             // expects error = false

    // Duration
    .durationShorterThan(Duration.ofMillis(100))
    .durationLongerThan(Duration.ofMillis(1))

    // Tags
    .tags(
        defaultTags(),                        // all standard DD tags
        tag("http.method", is("GET")),        // exact tag value
        tag("db.type", is("postgres")))

    // Span links
    .links(
        SpanLinkMatcher.to(otherSpan),
        SpanLinkMatcher.any())
```

### Tag matching

```java
// Default DD tags (thread name, runtime ID, sampling, etc.)
defaultTags()

// Exact value
tag("http.status", is(200))

// Custom validation
tag("response.body", validates(v -> ((String) v).contains("success")))

// Any value (just check presence)
tag("custom.tag", any())

// Error tags from exception
error(IOException.class)
error(IOException.class, "Connection refused")
error(new IOException("Connection refused"))

// Check tag presence without value check
includes("tag1", "tag2")
```

### Value matchers

```java
is("expected")           // equality
isNull()                 // null check
isNonNull()              // non-null check
isTrue()                 // boolean true
isFalse()                // boolean false
matches("regex.*")       // regex match
matches(Pattern.compile("..."))
validates(v -> ...)      // custom predicate
any()                    // accept anything
```

### Span link matching

```java
// Link to a specific span
SpanLinkMatcher.to(parentSpan)

// Link with trace/span IDs
SpanLinkMatcher.to(traceId, spanId)

// Link with additional properties
SpanLinkMatcher.to(span)
    .traceFlags((byte) 0x01)
    .traceState("vendor=value")

// Accept any link
SpanLinkMatcher.any()
```

### Sorting spans within a trace

```java
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;

assertTraces(
    trace(
        SORT_BY_START_TIME,
        span().root().operationName("parent"),
        span().childOfPrevious().operationName("child")));
```

### Waiting for traces

```java
// Wait until a condition is met (20s timeout)
blockUntilTracesMatch(traces -> traces.size() >= 2);

// Wait for child spans to finish
blockUntilChildSpansFinished(3);
```

### Accessing traces directly

For assertions not covered by the fluent API, access the writer directly:

```java
writer.waitForTraces(1);
List<DDSpan> trace = writer.firstTrace();
DDSpan span = trace.get(0);

assertEquals("expected-op", span.getOperationName().toString());
assertEquals(42L, span.getTag("custom.metric"));
```
