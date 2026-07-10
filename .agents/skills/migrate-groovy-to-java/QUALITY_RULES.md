# Groovy-to-Java Migration Quality Rules

Rules extracted from post-migration cleanup. Apply during initial generation and use as a review checklist.

## Severity levels

- **BLOCKER** — wrong semantics; test may pass by luck but is objectively incorrect. Always fix.
- **WARNING** — correctness risk or missed guarantee. Fix unless a utility class does not yet exist.
- **STYLE** — convention, readability, project standard. Fix where it improves clarity without added complexity.

BLOCKER rules include a grep pattern for mechanical detection.

---

## Category A: Imports & Static Imports

### RULE-A01: Static-import constants used 2+ times
- **Severity**: STYLE
- **Detection**: `ClassName.CONSTANT` repeated in the same file
- **Before**:
  ```java
  import datadog.trace.api.sampling.PrioritySampling;
  ...
  protected int priority = PrioritySampling.SAMPLER_KEEP;
  // ...
  assertEquals(PrioritySampling.UNSET, result);
  ```
- **After**:
  ```java
  import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
  import static datadog.trace.api.sampling.PrioritySampling.UNSET;
  ...
  protected int priority = SAMPLER_KEEP;
  // ...
  assertEquals(UNSET, result);
  ```
- **Notes**: Apply when the same `ClassName.MEMBER` appears 2 or more times. Single-use qualified references are fine.

---

### RULE-A02: Use named constants for @WithConfig keys
- **Severity**: WARNING
- **Detection**: `@WithConfig(key = "` with a literal string
- **Before**:
  ```java
  @WithConfig(key = "propagation.extract.log_header_names.enabled", value = "true")
  ```
- **After**:
  ```java
  import static datadog.trace.api.config.TracerConfig.PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED;
  ...
  @WithConfig(key = PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED, value = "true")
  ```
- **Notes**: Config key constants live in `datadog.trace.api.config.*`. Grep the constants classes for the matching field.

---

## Category B: Collection Simplification

### RULE-B01: Prefer HashMap over LinkedHashMap in tests
- **Severity**: STYLE
- **Detection**: `new LinkedHashMap<>()` in test files
- **Before**:
  ```java
  Map<String, String> headers = new LinkedHashMap<>();
  headers.put("x-trace-id", traceIdHex);
  headers.put("x-span-id", spanIdHex);
  ```
- **After**:
  ```java
  Map<String, String> headers = new HashMap<>();
  headers.put("x-trace-id", traceIdHex);
  headers.put("x-span-id", spanIdHex);
  ```
- **Notes**: Groovy's `[k: v]` literal uses `LinkedHashMap` but tests rarely need insertion-order guarantees. Use `LinkedHashMap` only when order is explicitly tested.

---

### RULE-B02: Use headers() helper for multi-entry header maps
- **Severity**: STYLE
- **Detection**: `new HashMap<>()` followed by 2+ `.put()` calls in test code (HTTP codec tests)
- **Before**:
  ```java
  Map<String, String> headers = new HashMap<>();
  headers.put(TRACE_ID_KEY.toUpperCase(), traceIdHex);
  headers.put(SPAN_ID_KEY.toUpperCase(), spanIdHex);
  headers.put(SAMPLING_PRIORITY_KEY, samplingPriority.toString());
  ```
- **After**:
  ```java
  // spotless:off
  Map<String, String> headers = headers(
      TRACE_ID_KEY, traceIdHex,
      SPAN_ID_KEY, spanIdHex,
      SAMPLING_PRIORITY_KEY, samplingPriority.toString());
  // spotless:on
  ```
- **Notes**: The `headers()` helper from `HttpCodecTestHelper` uppercases keys automatically. Remove manual `.toUpperCase()` calls. Pass `null` values to skip that entry. Only applicable when `HttpCodecTestHelper` is already in scope.

---

### RULE-B03: Use headers() instead of singletonMap
- **Severity**: STYLE
- **Detection**: `Collections.singletonMap(` or `singletonMap(` in test files
- **Before**:
  ```java
  Map<String, String> tagOnlyCtx = singletonMap("Forwarded", forwarded);
  ```
- **After**:
  ```java
  Map<String, String> tagOnlyCtx = headers("Forwarded", forwarded);
  ```
- **Notes**: Only when `HttpCodecTestHelper.headers()` is already used in the class. Otherwise `singletonMap` is fine.

---

## Category C: Assertion Modernization

### RULE-C01: Use assertInstanceOf instead of assertTrue + instanceof
- **Severity**: BLOCKER
- **Detection**: `assertTrue\(.*instanceof`
- **Before**:
  ```java
  assertTrue(context instanceof ExtractedContext);
  ```
- **After**:
  ```java
  assertInstanceOf(ExtractedContext.class, context);
  ```
- **Notes**: `assertInstanceOf` reports the actual type on failure; `assertTrue` only says "expected: true but was: false". Import: `org.junit.jupiter.api.Assertions.assertInstanceOf`.

---

### RULE-C02: Split compound instanceof-null assertions
- **Severity**: BLOCKER
- **Detection**: `assertTrue\(.*==\s*null.*instanceof`
- **Before**:
  ```java
  assertTrue(context == null || !(context instanceof ExtractedContext));
  ```
- **After**:
  ```java
  // If null is the expected outcome:
  assertNull(context);
  // If "not an ExtractedContext" is the intent:
  assertFalse(context instanceof ExtractedContext);
  // Or with a specific expected type:
  assertInstanceOf(TagContext.class, context);
  ```
- **Notes**: Compound boolean assertions hide which condition actually fired. Split so JUnit reports the exact failure. Read the Groovy original to determine the intent.

---

### RULE-C03: Add size assertion after map content assertions
- **Severity**: WARNING
- **Detection**: structural — file has 2+ `assertEquals(expected, carrier.get(` or `assertEquals(expected, map.get(` with no `assertEquals(N, *.size())`
- **Before**:
  ```java
  assertEquals("1", carrier.get("x-datadog-trace-id"));
  assertEquals("2", carrier.get("x-datadog-span-id"));
  ```
- **After**:
  ```java
  assertEquals("1", carrier.get("x-datadog-trace-id"));
  assertEquals("2", carrier.get("x-datadog-span-id"));
  assertEquals(2, carrier.size());
  ```
- **Notes**: Without a size check, injecting extra headers silently passes. Add at the end of each assertion block.

---

### RULE-C04: Use typed tag getters
- **Severity**: WARNING
- **Detection**: `\.getTags\(\)\.get\(`
- **Before**:
  ```java
  assertEquals("value", context.getTags().get(B3_TRACE_ID));
  ```
- **After**:
  ```java
  assertEquals("value", context.getTags().getString(B3_TRACE_ID));
  ```
- **Notes**: `getString()`, `getBoolean()`, `getLong()` avoid unsafe casts and produce better failure messages.

---

### RULE-C05: Preserve the original assertion's specificity — never relax to a matcher
- **Severity**: BLOCKER
- **Detection**: `any\(\)`, `anyInt\(\)`, `anyLong\(\)`, `anyString\(\)`, `anyByte\(\)`, `anyBoolean\(\)`, `atLeastOnce\(\)` — flag any that stand in for a literal value or exact cardinality the Groovy original pinned. Verify context (see Notes).
- **Before**:
  ```java
  // Groovy original: 1 * carrier.put(TRACE_ID_KEY, traceId)
  verify(carrier, atLeastOnce()).put(eq(TRACE_ID_KEY), anyString());
  ```
- **After**:
  ```java
  verify(carrier).put(TRACE_ID_KEY, traceId);
  // or, when the mock is replaced by a real carrier (RULE-D01):
  assertEquals(traceId, carrier.get(TRACE_ID_KEY));
  ```
- **Notes**: This is the highest-risk migration error: a relaxed assertion still compiles and passes, so the regression is invisible. A Spock interaction `N * mock.method(value)` pins **both** the cardinality and the exact argument. Port `1 * mock.m(v)` to `verify(mock).m(v)` (or `verify(mock, times(N)).m(v)` for `N *`), never `verify(mock, atLeastOnce()).m(any())`. Likewise never weaken a concrete `assertEquals(expected, actual)` into `assertTrue(actual != null)` or similar. The only legitimate matcher is a value that is genuinely non-deterministic at assertion time (e.g. a timestamp-derived id) — and even then prefer `assertNotNull(captured)` over `any()`. Cross-check every matcher against the Groovy original before accepting it.

---

## Category D: Mock Elimination

### RULE-D01: Replace Map mock + verify with concrete HashMap
- **Severity**: WARNING
- **Detection**: `mock\(.*Map.*\.class\)`
- **Before**:
  ```java
  Map<String, String> carrier = mock(Map.class);
  injector.inject(context, carrier, setter);
  verify(carrier).put("x-datadog-trace-id", "1");
  verify(carrier).put("x-datadog-span-id", "2");
  ```
- **After**:
  ```java
  Map<String, String> carrier = new HashMap<>();
  injector.inject(context, carrier, setter);
  assertEquals("1", carrier.get("x-datadog-trace-id"));
  assertEquals("2", carrier.get("x-datadog-span-id"));
  assertEquals(2, carrier.size()); // RULE-C03
  ```
- **Notes**: Mocking a Map tests the mock interactions, not the actual injected data. Use a real `HashMap`. Remove Mockito imports if no other mocks remain.

---

## Category E: Test Structure

### RULE-E01: Flatten lazy initialization into @BeforeEach
- **Severity**: STYLE
- **Detection**: structural — private field set to null + private method that checks null and initializes it
- **Before**:
  ```java
  private HttpCodec.Extractor lazyExtractor;

  private HttpCodec.Extractor extractor() {
    if (lazyExtractor == null) {
      lazyExtractor = W3CHttpCodec.newExtractor(config);
    }
    return lazyExtractor;
  }
  ```
- **After**:
  ```java
  private HttpCodec.Extractor extractor;

  @BeforeEach
  void setup() {
    this.extractor = W3CHttpCodec.newExtractor(config);
  }
  ```
- **Notes**: `@BeforeEach` is clearer and avoids the risk of stale state across tests if lazy init is skipped.

---

### RULE-E02: Replace abstract-class + single-boolean-override with concrete class + constant
- **Severity**: STYLE
- **Detection**: structural — abstract class whose subclasses only override one method returning a boolean
- **Before**:
  ```java
  abstract class B3HttpInjectorTest {
    protected abstract boolean tracePropagationB3Padding();
  }
  static class B3HttpInjectorPaddedTest extends B3HttpInjectorTest {
    @Override
    protected boolean tracePropagationB3Padding() { return true; }
  }
  ```
- **After**:
  ```java
  class B3HttpInjectorTest {
    protected boolean tracePropagationB3Padding() {
      return DEFAULT_PROPAGATION_B3_PADDING_ENABLED;
    }
  }
  ```
- **Notes**: The subclass that only flips a boolean adds noise. Use the config default as the base value. When the non-default variant matters, use `@WithConfig` on that test method.

---

### RULE-E03: Extract repeated helper methods to a utility class
- **Severity**: STYLE
- **Detection**: structural — same private helper method body appears in 2+ test files
- **Notes**: Create a `*TestHelper.java` in the same test package. Make methods `static`. Add overloads for common input types (`long`, `String`, `DDTraceId`) rather than casting at call sites.

---

## Category F: Type Correctness

### RULE-F01: Use byte for sampling priority and mechanism
- **Severity**: BLOCKER
- **Detection**: three separate greps (do not merge — `\b` plus `|` alternation misbehaves on some grep builds): `\bint\b.*[Ss]ampling[Pp]riority`, then `\bint\b.*\bpriority\b`, then `\bint\b.*\bmechanism\b`
- **Before**:
  ```java
  int expectedSamplingPriority = PrioritySampling.SAMPLER_KEEP;
  int mechanism = SamplingMechanism.DEFAULT;
  ```
- **After**:
  ```java
  byte expectedSamplingPriority = PrioritySampling.SAMPLER_KEEP;
  byte mechanism = SamplingMechanism.DEFAULT;
  ```
- **Notes**: `PrioritySampling` and `SamplingMechanism` constants are `byte`. Using `int` silently widens/narrows and can mask sign-extension bugs on comparisons.
  Apply **only** when the value is assigned from, or compared against, a `PrioritySampling.*`/`SamplingMechanism.*` constant — a plain `int` counter, loop index, or unrelated field that happens to contain the word "priority"/"mechanism" is a false positive.
  The detection pattern is broad; verify context before changing (and before auto-fixing, since `int` → `byte` can break compilation).

---

## Category G: Code Style

### RULE-G01: Remove inline parameter-name comments
- **Severity**: STYLE
- **Detection**: `/\* [a-z]`
- **Before**:
  ```java
  OrgGuardEnforcer.enforce(/* origin */ null, /* httpHeaders */ null);
  ```
- **After**:
  ```java
  OrgGuardEnforcer.enforce(null, null);
  ```
- **Notes**: AI-generated artifact. These drift silently when parameters are renamed or reordered. Remove unconditionally.

---

### RULE-G02: Use method references for zero-arg lambdas
- **Severity**: STYLE
- **Detection**: `\(\) -> [a-zA-Z]+\.[a-zA-Z]+\(\)`
- **Before**:
  ```java
  scheduler.schedule(() -> dynamicConfig.captureTraceConfig(), 1, TimeUnit.SECONDS);
  ```
- **After**:
  ```java
  scheduler.schedule(dynamicConfig::captureTraceConfig, 1, TimeUnit.SECONDS);
  ```
- **Notes**: Apply only when the lambda body is a single no-arg method call with no surrounding logic.

---

### RULE-G04: Trim trailing whitespace padding in @TableTest tables
- **Severity**: STYLE
- **Detection**: `".*\s\s+\|` or trailing spaces before closing `"` in `@TableTest` strings
- **Notes**: Trailing spaces added by formatters or LLMs for alignment serve no purpose and cause noisy diffs. Each cell value should be trimmed to its natural width; column alignment should come from the `|` delimiters only.

---

### RULE-G05: Use try-with-resources instead of close() in a finally block
- **Severity**: STYLE
- **Detection**: `\} finally \{` whose block body is a single `.close()` call. Verify context (see Notes).
- **Before**:
  ```java
  AnnotationConfigApplicationContext context =
      new AnnotationConfigApplicationContext(IntervalTaskConfig.class, SchedulingConfig.class);
  try {
    IntervalTask task = context.getBean(IntervalTask.class);
    task.blockUntilExecute();
    assertScheduledTraces("IntervalTask.run");
  } finally {
    context.close();
  }
  ```
- **After**:
  ```java
  try (AnnotationConfigApplicationContext context =
      new AnnotationConfigApplicationContext(IntervalTaskConfig.class, SchedulingConfig.class)) {
    IntervalTask task = context.getBean(IntervalTask.class);
    task.blockUntilExecute();
    assertScheduledTraces("IntervalTask.run");
  }
  ```
- **Notes**: Spock's `cleanup:` block is commonly ported to `try { ... } finally { resource.close(); }`. When the resource type is `Closeable`/`AutoCloseable` and the `finally` only closes it, use try-with-resources — it is shorter, scopes the resource to the block, and reports `close()` failures as suppressed exceptions. **Only when `close()` is the finally's sole statement and there is no surrounding logic.** Do not convert when the resource must be closed at a specific earlier point (e.g. `SpringSchedulingTest.scheduleTriggerTestAccordingToCronExpression` closes the context mid-test to stop further scheduled traces before asserting), nor when the `finally` does more than close (e.g. `reset()` plus buffer bookkeeping).

---

## Category H: @TableTest Data Quality

### RULE-H01: Replace magic trace ID strings with symbolic names
- **Severity**: WARNING
- **Detection**: `'18446744073709551[0-9]+'` in `@TableTest` strings
- **Before**:
  ```java
  @TableTest({
    "scenario          | traceId                  ",
    "uint64 max        | '18446744073709551615'    ",
    "uint64 max-1      | '18446744073709551614'    "
  })
  void extractHeaders(@ConvertWith(TraceIdConverter.class) String traceId) { ... }
  ```
- **After**:
  ```java
  @TableTest({
    "scenario     | traceId         ",
    "uint64 max   | 'TRACE_ID_MAX'  ",
    "uint64 max-1 | 'TRACE_ID_MAX-1'"
  })
  void extractHeaders(@ConvertWith(TraceIdConverter.class) String traceId) { ... }
  ```
- **Notes**: `TraceIdConverter` (in `datadog.trace.junit.utils.tabletest`) resolves `TRACE_ID_MAX`, `TRACE_ID_MAX-1`, and `TRACE_ID_MAX+1`. Add `@ConvertWith(TraceIdConverter.class)` to the parameter.

---

### RULE-H02: Use unqualified enum names in table strings with a TypeConvertor
- **Severity**: STYLE
- **Detection**: structural — `@TableTest` rows containing `ClassName.CONSTANT` strings where a `TypeConvertor` for that class exists
- **Before**:
  ```java
  "no priority | PrioritySampling.UNSET       ",
  "keep        | PrioritySampling.SAMPLER_KEEP ",
  ```
- **After**:
  ```java
  "no priority | UNSET       ",
  "keep        | SAMPLER_KEEP",
  ```
- **Notes**: Requires a `TypeConvertor<PrioritySampling>` (e.g., `PrioritySamplingConverter`) registered or passed via `@ConvertWith`. Check whether the converter already exists before applying.

---

## Category I: Visibility & Annotations

### RULE-I01: Annotate production constants exposed for tests
- **Severity**: STYLE
- **Detection**: structural — production class constant changed from `private` to `public` or package-private without annotation
- **Before**:
  ```java
  // In production class
  private static final int MAX_MEMBER_COUNT = 32;
  ```
- **After**:
  ```java
  // In production class
  @VisibleForTesting
  public static final int MAX_MEMBER_COUNT = 32;
  ```
- **Notes**: Import: `datadog.trace.api.internal.VisibleForTesting`. Do not apply to constants that are genuinely public API.

---

### RULE-I02: Remove unnecessary private modifier from test constants
- **Severity**: STYLE
- **Detection**: `private static final` in test files where the constant is used in other test files in the same package
- **Before**:
  ```java
  private static final String B3_TRACE_ID = "b3.traceid";
  ```
- **After**:
  ```java
  static final String B3_TRACE_ID = "b3.traceid";
  ```
- **Notes**: Package-private visibility is sufficient for test-internal sharing. Only upgrade to `public` if the constant is needed across packages.

---

## Category J: API Correctness

### RULE-J02: Use ContextVisitors.stringValuesMap() for Map<String,String> carriers
- **Severity**: WARNING
- **Detection**: `CarrierVisitor\|forEachKeyValue`
- **Before**:
  ```java
  private static final CarrierVisitor<Map<String, String>> VISITOR =
      new CarrierVisitor<Map<String, String>>() {
        @Override
        public void forEachKeyValue(Map<String, String> carrier, BiConsumer<String, String> c) {
          carrier.forEach(c);
        }
      };
  propagator.extract(ctx, headers, VISITOR);
  ```
- **After**:
  ```java
  import static datadog.trace.bootstrap.instrumentation.api.ContextVisitors.stringValuesMap;
  ...
  propagator.extract(ctx, headers, stringValuesMap());
  ```
- **Notes**: `ContextVisitors.stringValuesMap()` is the canonical visitor for `Map<String,String>`. Check existing usages in `HttpExtractorTest`, `W3CHttpExtractorTest`, `HaystackHttpExtractorTest` for reference.
