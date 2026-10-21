package datadog.trace.agent.test.assertions;

import static datadog.trace.test.junit.utils.assertions.Matchers.assertValue;
import static datadog.trace.test.junit.utils.assertions.Matchers.is;
import static datadog.trace.test.junit.utils.assertions.Matchers.validates;
import static java.util.Collections.emptyMap;

import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanEvent;
import datadog.trace.test.junit.utils.assertions.Matcher;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Provides matchers for span events based on their properties such as name, time, and attributes.
 */
public final class SpanEventMatcher {
  private final Matcher<String> nameMatcher;
  private Matcher<Long> timeMatcher;
  private Matcher<Map<String, ?>> attributesMatcher;

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
   * Sets the event time to match against.
   *
   * @param timestamp The event time to match against.
   * @param unit The event time unit.
   * @return The updated {@code SpanEventMatcher} instance with the new time constraint.
   */
  public SpanEventMatcher time(long timestamp, TimeUnit unit) {
    this.timeMatcher = is(unit.toNanos(timestamp));
    return this;
  }

  /**
   * Sets the event attributes to match against.
   *
   * @param attributes The event attributes to match against, with values being {@link String},
   *     {@link Boolean}, {@link Long}, {@link Double}, or a {@link List} of them.
   * @return The updated {@code SpanEventMatcher} instance with the new attributes constraint.
   */
  public SpanEventMatcher attributes(Map<String, ?> attributes) {
    this.attributesMatcher = is(attributes);
    return this;
  }

  void assertEvent(DDSpan span, DDSpanEvent event) {
    Matcher<Long> timeMatcher = this.timeMatcher;
    if (timeMatcher == null) {
      long startTime = span.getStartTime();
      long endTime = startTime + span.getDurationNano();
      timeMatcher = validates(time -> startTime <= time && time <= endTime);
    }
    // Assert event values
    assertValue(this.nameMatcher, event.name(), "Unexpected event name");
    assertValue(timeMatcher, event.timeUnixNano(), "Unexpected event time");
    assertValue(this.attributesMatcher, event.attributes(), "Unexpected event attributes");
  }
}
