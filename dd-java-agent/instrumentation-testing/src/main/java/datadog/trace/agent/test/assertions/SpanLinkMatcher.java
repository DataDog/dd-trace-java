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

  public static SpanLinkMatcher from(DDSpan span) {
    return from(span.context());
  }

  public static SpanLinkMatcher from(AgentSpanContext spanContext) {
    return from(spanContext.getTraceId(), spanContext.getSpanId());
  }

  public static SpanLinkMatcher from(DDTraceId traceId, long spanId) {
    return new SpanLinkMatcher(is(traceId), is(spanId));
  }

  public static SpanLinkMatcher any() {
    return new SpanLinkMatcher(Matchers.any(), Matchers.any());
  }

  public SpanLinkMatcher traceFlags(byte traceFlags) {
    this.traceFlagsMatcher = is(traceFlags);
    return this;
  }

  public SpanLinkMatcher attributes(SpanAttributes spanAttributes) {
    this.spanAttributesMatcher = is(spanAttributes);
    return this;
  }

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
