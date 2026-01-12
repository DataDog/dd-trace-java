package datadog.trace.agent.test.assertions;

import static datadog.trace.agent.test.assertions.Matchers.assertValue;
import static datadog.trace.agent.test.assertions.Matchers.is;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpanLink.DEFAULT_FLAGS;
import static datadog.trace.bootstrap.instrumentation.api.SpanAttributes.EMPTY;

import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.SpanAttributes;
import datadog.trace.core.DDSpan;

/**
 * Provides matchers for span links based on their properties such as trace and span IDs links refer
 * to, trace flags, span attributes, and trace state.
 */
public final class SpanLinkMatcher {
  private final Matcher<DDTraceId> traceIdMatcher;
  private final Matcher<Long> spanIdMatcher;
  private Matcher<Byte> traceFlagsMatcher;
  private Matcher<SpanAttributes> spanAttributesMatcher;
  private Matcher<String> traceStateMatcher;

  private SpanLinkMatcher(Matcher<DDTraceId> traceIdMatcher, Matcher<Long> spanIdMatcher) {
    this.traceIdMatcher = traceIdMatcher;
    this.spanIdMatcher = spanIdMatcher;
    this.traceFlagsMatcher = is(DEFAULT_FLAGS);
    this.spanAttributesMatcher = is(EMPTY);
    this.traceStateMatcher = is("");
  }

  /**
   * Creates a {@code SpanLinkMatcher} that matches a span link to the given span.
   *
   * @param span The span the link should match to.
   * @return A {@code SpanLinkMatcher} that matches a span link to the given span.
   */
  public static SpanLinkMatcher to(DDSpan span) {
    return to(span.context());
  }

  /**
   * Creates a {@code SpanLinkMatcher} that matches a span link to the given span context.
   *
   * @param spanContext The span context the span link should match to.
   * @return A {@code SpanLinkMatcher} that matches a span link to the given span context.
   */
  public static SpanLinkMatcher to(AgentSpanContext spanContext) {
    return to(spanContext.getTraceId(), spanContext.getSpanId());
  }

  /**
   * Creates a {@code SpanLinkMatcher} that matches a span link to the given trace / span
   * identifiers.
   *
   * @param traceId The trace ID the span link should match to.
   * @param spanId The trace ID the span link should match to.
   * @return A {@code SpanLinkMatcher} that matches a span link to the given trace / span
   *     identifiers.
   */
  public static SpanLinkMatcher to(DDTraceId traceId, long spanId) {
    return new SpanLinkMatcher(is(traceId), is(spanId));
  }

  /**
   * Creates a {@code SpanLinkMatcher} that matches any span link.
   *
   * @return A {@code SpanLinkMatcher} that matches any span link.
   */
  public static SpanLinkMatcher any() {
    return new SpanLinkMatcher(Matchers.any(), Matchers.any());
  }

  /**
   * Sets the trace flags value to match against.
   *
   * @param traceFlags The byte value representing the trace flags to match against.
   * @return The updated {@code SpanLinkMatcher} instance with the new trace flags constraint.
   */
  public SpanLinkMatcher traceFlags(byte traceFlags) {
    this.traceFlagsMatcher = is(traceFlags);
    return this;
  }

  /**
   * Sets the span attributes value to match against.
   *
   * @param spanAttributes The span attributes to match against.
   * @return The updated {@code SpanLinkMatcher} instance with the new span attributes constraint.
   */
  public SpanLinkMatcher attributes(SpanAttributes spanAttributes) {
    this.spanAttributesMatcher = is(spanAttributes);
    return this;
  }

  /**
   * Sets the trace state value to match against.
   *
   * @param traceState The trace state to match against.
   * @return The updated {@code SpanLinkMatcher} instance with the new trace state constraint.
   */
  public SpanLinkMatcher traceState(String traceState) {
    this.traceStateMatcher = is(traceState);
    return this;
  }

  void assertLink(AgentSpanLink link) {
    // Assert link values
    assertValue(this.traceIdMatcher, link.traceId(), "Expected trace identifier");
    assertValue(this.spanIdMatcher, link.spanId(), "Expected span identifier");
    assertValue(this.traceFlagsMatcher, link.traceFlags(), "Expected trace flags");
    assertValue(this.spanAttributesMatcher, link.attributes(), "Expected attributes");
    assertValue(this.traceStateMatcher, link.traceState(), "Expected trace state");
  }
}
