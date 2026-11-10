package datadog.smoketest.trace;

import static datadog.trace.test.junit.utils.assertions.Matchers.assertValue;
import static datadog.trace.test.junit.utils.assertions.Matchers.is;
import static datadog.trace.test.junit.utils.assertions.Matchers.validates;
import static java.util.Collections.emptyMap;

import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedSpanEvent;
import datadog.trace.test.junit.utils.assertions.Matcher;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Provides matchers for decoded span events based on their properties such as name, time, and
 * attributes.
 */
public final class SpanEventMatcher {
  private final Matcher<String> nameMatcher;
  private Matcher<Long> timeMatcher;
  private Matcher<Map<String, Object>> attributesMatcher;

  private SpanEventMatcher(Matcher<String> nameMatcher) {
    this.nameMatcher = nameMatcher;
    this.attributesMatcher = is(emptyMap());
  }

  /**
   * Creates a {@code SpanEventMatcher} that matches a span event with the given name. By default,
   * the event must have no attribute and its time must be within the span start and end times.
   *
   * @param name The span event name to match against.
   * @return A {@code SpanEventMatcher} that matches a span event with the given name.
   */
  public static SpanEventMatcher event(String name) {
    return new SpanEventMatcher(is(name));
  }

  /**
   * Checks the event time matches the given value.
   *
   * @param timestamp The event time to match against.
   * @param unit The event time unit.
   * @return The current {@link SpanEventMatcher} instance with the time constraint applied.
   */
  public SpanEventMatcher time(long timestamp, TimeUnit unit) {
    this.timeMatcher = is(unit.toNanos(timestamp));
    return this;
  }

  /**
   * Checks the event time matches the given matcher.
   *
   * @param matcher The matcher to check the event time, in nanoseconds since the Unix epoch.
   * @return The current {@link SpanEventMatcher} instance with the time constraint applied.
   */
  public SpanEventMatcher time(Matcher<Long> matcher) {
    this.timeMatcher = matcher;
    return this;
  }

  /**
   * Checks the event attributes match the given values.
   *
   * @param attributes The event attributes to match against, with values being {@link String},
   *     {@link Boolean}, {@link Long}, {@link Double}, or a {@link List} of them.
   * @return The current {@link SpanEventMatcher} instance with the attributes constraint applied.
   */
  public SpanEventMatcher attributes(Map<String, Object> attributes) {
    this.attributesMatcher = is(attributes);
    return this;
  }

  /**
   * Checks the event attributes match the given matcher.
   *
   * @param matcher The matcher to check the event attributes.
   * @return The current {@link SpanEventMatcher} instance with the attributes constraint applied.
   */
  public SpanEventMatcher attributes(Matcher<Map<String, Object>> matcher) {
    this.attributesMatcher = matcher;
    return this;
  }

  void assertEvent(DecodedSpan span, DecodedSpanEvent event, int eventIndex) {
    String at = " (event #" + eventIndex + ")";
    Matcher<Long> timeMatcher = this.timeMatcher;
    if (timeMatcher == null) {
      long startTime = span.getStart();
      long endTime = startTime + span.getDuration();
      timeMatcher = validates(time -> startTime <= time && time <= endTime);
    }
    assertValue(this.nameMatcher, event.getName(), "Unexpected span event name" + at);
    assertValue(timeMatcher, event.getTimeUnixNano(), "Unexpected span event time" + at);
    assertValue(
        this.attributesMatcher, event.getAttributes(), "Unexpected span event attributes" + at);
  }
}
