package datadog.smoketest.trace;

import static datadog.trace.junit.utils.assertions.Matchers.assertValue;
import static datadog.trace.junit.utils.assertions.Matchers.is;
import static datadog.trace.junit.utils.assertions.Matchers.isFalse;
import static datadog.trace.junit.utils.assertions.Matchers.isTrue;

import datadog.trace.junit.utils.assertions.Matcher;
import datadog.trace.test.agent.decoder.DecodedSpan;
import java.util.HashMap;
import java.util.Map;

/**
 * Thin span matcher for smoke tests, operating on the <em>serialized</em> {@link DecodedSpan} model
 * produced by both smoke backends (the in-process mock agent and the dd-apm-test-agent).
 *
 * <p>This is deliberately separate from the in-process instrumentation DSL ({@code
 * datadog.trace.agent.test.assertions}, which matches on {@code DDSpan}); the two share only the
 * generic value matchers in {@code datadog.trace.junit.utils.assertions}. Only what a span carries
 * once serialized is matchable here: name/service/resource/type, error status, {@code meta} (string
 * tags), {@code metrics} (numeric tags), and parent linkage.
 *
 * <p>Use the {@link #span()} factory as a fluent builder. Unset constraints are not checked (thin
 * by design).
 */
public final class SpanMatcher {
  private Matcher<String> serviceMatcher;
  private Matcher<String> operationNameMatcher;
  private Matcher<String> resourceNameMatcher;
  private Matcher<String> typeMatcher;
  private Matcher<Boolean> errorMatcher;
  private Long parentId; // null => parent not checked
  private final Map<String, Matcher<?>> metaMatchers = new HashMap<>();
  private final Map<String, Matcher<?>> metricMatchers = new HashMap<>();

  private SpanMatcher() {}

  /** Creates a new span matcher; configure it with the fluent methods below. */
  public static SpanMatcher span() {
    return new SpanMatcher();
  }

  public SpanMatcher service(String service) {
    this.serviceMatcher = is(service);
    return this;
  }

  public SpanMatcher operationName(String operationName) {
    this.operationNameMatcher = is(operationName);
    return this;
  }

  public SpanMatcher resourceName(String resourceName) {
    this.resourceNameMatcher = is(resourceName);
    return this;
  }

  public SpanMatcher type(String type) {
    this.typeMatcher = is(type);
    return this;
  }

  /** Matches a non-error span. */
  public SpanMatcher error(boolean errored) {
    this.errorMatcher = errored ? isTrue() : isFalse();
    return this;
  }

  /** Matches a root span (no parent). */
  public SpanMatcher root() {
    this.parentId = 0L;
    return this;
  }

  /** Matches a span whose parent is the given span id. */
  public SpanMatcher childOf(long parentSpanId) {
    this.parentId = parentSpanId;
    return this;
  }

  /** Matches a {@code meta} (string) tag against the given matcher. */
  public SpanMatcher tag(String name, Matcher<?> matcher) {
    this.metaMatchers.put(name, matcher);
    return this;
  }

  /** Matches a {@code meta} (string) tag against the given value. */
  public SpanMatcher tag(String name, String value) {
    this.metaMatchers.put(name, is(value));
    return this;
  }

  /** Matches a {@code metrics} (numeric) tag against the given matcher. */
  public SpanMatcher metric(String name, Matcher<?> matcher) {
    this.metricMatchers.put(name, matcher);
    return this;
  }

  // TODO thin first cut — add when a smoke test needs them:
  //  - operationName/resourceName by Pattern or Predicate,
  //  - childOf(SpanMatcher) resolving the parent within the trace,
  //  - exhaustive tag coverage (fail on unexpected meta/metrics), and span-links.

  @SuppressWarnings("unchecked")
  void assertSpan(DecodedSpan span) {
    assertValue(serviceMatcher, span.getService(), "Unexpected service name");
    assertValue(operationNameMatcher, span.getName(), "Unexpected operation name");
    assertValue(resourceNameMatcher, span.getResource(), "Unexpected resource name");
    assertValue(typeMatcher, span.getType(), "Unexpected span type");
    assertValue(errorMatcher, span.getError() != 0, "Unexpected error status");
    if (parentId != null) {
      assertValue(is(parentId), span.getParentId(), "Unexpected parent id");
    }
    Map<String, String> meta = span.getMeta();
    for (Map.Entry<String, Matcher<?>> entry : metaMatchers.entrySet()) {
      Object value = meta == null ? null : meta.get(entry.getKey());
      assertValue(
          (Matcher<Object>) entry.getValue(),
          value,
          "Unexpected meta tag '" + entry.getKey() + "'");
    }
    Map<String, Number> metrics = span.getMetrics();
    for (Map.Entry<String, Matcher<?>> entry : metricMatchers.entrySet()) {
      Object value = metrics == null ? null : metrics.get(entry.getKey());
      assertValue(
          (Matcher<Object>) entry.getValue(), value, "Unexpected metric '" + entry.getKey() + "'");
    }
  }
}
