package datadog.smoketest.trace;

import static datadog.trace.test.junit.utils.assertions.Matchers.assertValue;
import static datadog.trace.test.junit.utils.assertions.Matchers.is;
import static java.util.Collections.emptyMap;

import datadog.trace.api.DDTraceId;
import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedSpanLink;
import datadog.trace.test.junit.utils.assertions.Matcher;
import datadog.trace.test.junit.utils.assertions.Matchers;
import java.util.List;
import java.util.Map;

/**
 * This class is a helper class to verify a span link on the {@link DecodedSpanLink} model produced
 * by smoke backends.
 *
 * <p>Get a {@code SpanLinkMatcher} from {@link #toIndex(int)}, {@link #to(long, long)} or {@link
 * #any()}, then refine it with the trace flags, trace state and attributes the link should carry.
 * Pass the result to {@link SpanMatcher#links(SpanLinkMatcher...)}.
 *
 * @see SpanMatcher#links(SpanLinkMatcher...)
 */
public final class SpanLinkMatcher {
  private static final int NO_SPAN_INDEX = -1;

  private final Matcher<Long> traceIdMatcher;
  private final Matcher<Long> spanIdMatcher;
  private final int targetSpanIndex;
  private Matcher<Byte> traceFlagsMatcher;
  private Matcher<String> traceStateMatcher;
  private Matcher<Map<String, String>> attributesMatcher;

  private SpanLinkMatcher(
      Matcher<Long> traceIdMatcher, Matcher<Long> spanIdMatcher, int targetSpanIndex) {
    this.targetSpanIndex = targetSpanIndex;
    this.traceIdMatcher = traceIdMatcher;
    this.spanIdMatcher = spanIdMatcher;
    this.traceFlagsMatcher = is((byte) 0);
    this.traceStateMatcher = is("");
    this.attributesMatcher = is(emptyMap());
  }

  /**
   * Checks the link refers to the given trace / span identifiers, for a link to a span outside the
   * asserted trace.
   *
   * @param traceId The trace identifier the link should refer to.
   * @param spanId The span identifier the link should refer to.
   * @return A new {@link SpanLinkMatcher} instance matching a link to those identifiers.
   */
  public static SpanLinkMatcher to(DDTraceId traceId, long spanId) {
    return new SpanLinkMatcher(is(traceId.toLong()), is(spanId), NO_SPAN_INDEX);
  }

  /**
   * Checks the link refers to the given trace / span identifiers, for a link to a span outside the
   * asserted trace.
   *
   * @param traceId The trace identifier the link should refer to.
   * @param spanId The span identifier the link should refer to.
   * @return A new {@link SpanLinkMatcher} instance matching a link to those identifiers.
   */
  public static SpanLinkMatcher to(long traceId, long spanId) {
    return new SpanLinkMatcher(is(traceId), is(spanId), NO_SPAN_INDEX);
  }

  /**
   * Checks the link refers to the span at the specified index in the trace, resolved once the
   * trace's spans have been sorted.
   *
   * @param spanIndex The index of the linked span in the trace.
   * @return A new {@link SpanLinkMatcher} instance matching a link to that span.
   */
  public static SpanLinkMatcher toIndex(int spanIndex) {
    if (spanIndex < 0) {
      throw new IllegalArgumentException("index must be >= 0");
    }
    return new SpanLinkMatcher(null, null, spanIndex);
  }

  /**
   * Checks a link is present, whichever span it refers to. Useful to assert a link count, or to
   * skip over a link whose target does not matter.
   *
   * @return A new {@link SpanLinkMatcher} instance matching any link.
   */
  public static SpanLinkMatcher any() {
    return new SpanLinkMatcher(Matchers.any(), Matchers.any(), NO_SPAN_INDEX);
  }

  /**
   * Checks the link trace flags match the given value.
   *
   * @param traceFlags The W3C trace flags to match against.
   * @return The current {@link SpanLinkMatcher} instance with the trace flags constraint applied.
   */
  public SpanLinkMatcher traceFlags(byte traceFlags) {
    this.traceFlagsMatcher = is(traceFlags);
    return this;
  }

  /**
   * Checks the link trace flags match the given matcher.
   *
   * @param matcher The matcher to check the W3C trace flags.
   * @return The current {@link SpanLinkMatcher} instance with the trace flags constraint applied.
   */
  public SpanLinkMatcher traceFlags(Matcher<Byte> matcher) {
    this.traceFlagsMatcher = matcher;
    return this;
  }

  /**
   * Checks the link trace state matches the given value.
   *
   * @param traceState The W3C trace state to match against.
   * @return The current {@link SpanLinkMatcher} instance with the trace state constraint applied.
   */
  public SpanLinkMatcher traceState(String traceState) {
    this.traceStateMatcher = is(traceState);
    return this;
  }

  /**
   * Checks the link trace state matches the given matcher.
   *
   * @param matcher The matcher to check the W3C trace state.
   * @return The current {@link SpanLinkMatcher} instance with the trace state constraint applied.
   */
  public SpanLinkMatcher traceState(Matcher<String> matcher) {
    this.traceStateMatcher = matcher;
    return this;
  }

  /**
   * Checks the link attributes match the given values.
   *
   * @param attributes The link attributes to match against.
   * @return The current {@link SpanLinkMatcher} instance with the attributes constraint applied.
   */
  public SpanLinkMatcher attributes(Map<String, String> attributes) {
    this.attributesMatcher = is(attributes);
    return this;
  }

  /**
   * Checks the link attributes match the given matcher.
   *
   * @param matcher The matcher to check the link attributes.
   * @return The current {@link SpanLinkMatcher} instance with the attributes constraint applied.
   */
  public SpanLinkMatcher attributes(Matcher<Map<String, String>> matcher) {
    this.attributesMatcher = matcher;
    return this;
  }

  void assertLink(List<DecodedSpan> trace, DecodedSpanLink link, int linkIndex) {
    String at = " (link #" + linkIndex + ")";
    assertValue(
        traceIdMatcher(trace), link.getTraceId(), "Unexpected span link trace identifier" + at);
    assertValue(spanIdMatcher(trace), link.getSpanId(), "Unexpected span link identifier" + at);
    assertValue(this.traceFlagsMatcher, link.getTraceFlags(), "Unexpected span link flags" + at);
    assertValue(
        this.traceStateMatcher, link.getTraceState(), "Unexpected span link trace state" + at);
    assertValue(
        this.attributesMatcher, link.getAttributes(), "Unexpected span link attributes" + at);
  }

  private Matcher<Long> traceIdMatcher(List<DecodedSpan> trace) {
    return this.targetSpanIndex >= 0 ? is(targetSpan(trace).getTraceId()) : this.traceIdMatcher;
  }

  private Matcher<Long> spanIdMatcher(List<DecodedSpan> trace) {
    return this.targetSpanIndex >= 0 ? is(targetSpan(trace).getSpanId()) : this.spanIdMatcher;
  }

  private DecodedSpan targetSpan(List<DecodedSpan> trace) {
    if (this.targetSpanIndex >= trace.size()) {
      throw new IllegalStateException(
          "Cannot link to span #"
              + this.targetSpanIndex
              + ": the trace holds only "
              + trace.size()
              + " span(s)");
    }
    return trace.get(this.targetSpanIndex);
  }
}
