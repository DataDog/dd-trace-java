package datadog.smoketest.trace;

import static datadog.trace.test.junit.utils.assertions.Matchers.any;
import static datadog.trace.test.junit.utils.assertions.Matchers.assertValue;
import static datadog.trace.test.junit.utils.assertions.Matchers.is;
import static datadog.trace.test.junit.utils.assertions.Matchers.isFalse;
import static datadog.trace.test.junit.utils.assertions.Matchers.isTrue;
import static datadog.trace.test.junit.utils.assertions.Matchers.matches;
import static datadog.trace.test.junit.utils.assertions.Matchers.validates;

import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.junit.utils.assertions.Matcher;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

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
 * <p>Parent linkage can be expressed by {@link #root()}, {@link #childOf(long) explicit id}, or —
 * for structural assertions within a sorted trace — by position with {@link #childOfIndex(int)} /
 * {@link #childOfPrevious()} (mirroring the instrumentation DSL). Use the {@link #span()} factory
 * as a fluent builder; unset constraints are not checked.
 */
public final class SpanMatcher {
  private Matcher<String> serviceMatcher;
  private Matcher<CharSequence> operationNameMatcher;
  private Matcher<CharSequence> resourceNameMatcher;
  private Matcher<String> typeMatcher;
  private Matcher<Boolean> errorMatcher;
  private Long parentId; // explicit expected parent id (childOf/root); null => not checked by id
  private int parentSpanIndex = -1; // >= 0 => parent is the span at this index in the sorted trace
  private boolean childOfPrevious; // parent is the preceding span in the sorted trace
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

  public SpanMatcher operationName(Pattern pattern) {
    this.operationNameMatcher = matches(pattern);
    return this;
  }

  public SpanMatcher resourceName(String resourceName) {
    this.resourceNameMatcher = is(resourceName);
    return this;
  }

  public SpanMatcher resourceName(Pattern pattern) {
    this.resourceNameMatcher = matches(pattern);
    return this;
  }

  public SpanMatcher resourceName(Predicate<CharSequence> validator) {
    this.resourceNameMatcher = validates(validator);
    return this;
  }

  public SpanMatcher type(String type) {
    this.typeMatcher = is(type);
    return this;
  }

  /** Matches the span error status. */
  public SpanMatcher error(boolean errored) {
    this.errorMatcher = errored ? isTrue() : isFalse();
    return this;
  }

  /** Matches a root span (no parent). */
  public SpanMatcher root() {
    this.parentId = 0L;
    this.parentSpanIndex = -1;
    this.childOfPrevious = false;
    return this;
  }

  /** Matches a span whose parent is the given span id. */
  public SpanMatcher childOf(long parentSpanId) {
    this.parentId = parentSpanId;
    this.parentSpanIndex = -1;
    this.childOfPrevious = false;
    return this;
  }

  /** Matches a span whose parent is the span at {@code parentSpanIndex} in the (sorted) trace. */
  public SpanMatcher childOfIndex(int parentSpanIndex) {
    this.parentSpanIndex = parentSpanIndex;
    this.parentId = null;
    this.childOfPrevious = false;
    return this;
  }

  /** Matches a span whose parent is the immediately preceding span in the (sorted) trace. */
  public SpanMatcher childOfPrevious() {
    this.childOfPrevious = true;
    this.parentId = null;
    this.parentSpanIndex = -1;
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

  // TODO thin: exhaustive tag coverage (fail on unexpected meta/metrics) and span-links.

  /** Positional (count-exact) assertion: the span at {@code spanIndex} matches this matcher. */
  void assertSpan(List<DecodedSpan> trace, int spanIndex) {
    assertValue(
        parentIdMatcher(trace, spanIndex),
        trace.get(spanIndex).getParentId(),
        "Unexpected parent id");
    assertFields(trace.get(spanIndex));
  }

  /** Asserts a span's own fields (service/operation/resource/type/error/tags), ignoring linkage. */
  @SuppressWarnings("unchecked")
  private void assertFields(DecodedSpan span) {
    assertValue(serviceMatcher, span.getService(), "Unexpected service name");
    assertValue(operationNameMatcher, span.getName(), "Unexpected operation name");
    assertValue(resourceNameMatcher, span.getResource(), "Unexpected resource name");
    assertValue(typeMatcher, span.getType(), "Unexpected span type");
    assertValue(errorMatcher, span.getError() != 0, "Unexpected error status");
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

  private Matcher<Long> parentIdMatcher(List<DecodedSpan> trace, int spanIndex) {
    if (this.parentSpanIndex >= 0) {
      return is(trace.get(parentSpanIndex).getSpanId());
    }
    if (this.childOfPrevious) {
      if (spanIndex == 0) {
        throw new IllegalStateException("childOfPrevious() cannot be used on the first span");
      }
      return is(trace.get(spanIndex - 1).getSpanId());
    }
    return this.parentId == null ? any() : is(this.parentId);
  }
}
