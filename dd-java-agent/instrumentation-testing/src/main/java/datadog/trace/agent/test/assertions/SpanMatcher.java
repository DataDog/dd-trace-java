package datadog.trace.agent.test.assertions;

import static datadog.trace.agent.test.assertions.Matchers.is;
import static datadog.trace.agent.test.assertions.Matchers.matches;
import static datadog.trace.agent.test.assertions.Matchers.nonNull;
import static datadog.trace.agent.test.assertions.Matchers.validates;
import static java.time.temporal.ChronoUnit.NANOS;

import datadog.trace.core.DDSpan;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.opentest4j.AssertionFailedError;

public final class SpanMatcher {
  private Matcher<Long> idMatcher;
  private Matcher<Long> parentIdMatcher;
  private Matcher<String> serviceNameMatcher;
  private Matcher<CharSequence> operationNameMatcher;
  private Matcher<CharSequence> resourceNameMatcher;
  private Matcher<Duration> durationMatcher;

  private static final Matcher<Long> CHILD_OF_PREVIOUS_MATCHER = is(0L);

  private SpanMatcher() {}

  public static SpanMatcher span() {
    return new SpanMatcher();
  }

  public SpanMatcher withId(long id) {
    this.idMatcher = is(id);
    return this;
  }

  public SpanMatcher isRoot() {
    return childOf(0L);
  }

  public SpanMatcher childOf(long parentId) {
    this.parentIdMatcher = is(parentId);
    return this;
  }

  public SpanMatcher childOfPrevious() {
    this.parentIdMatcher = CHILD_OF_PREVIOUS_MATCHER;
    return this;
  }

  public SpanMatcher hasServiceName() {
    this.serviceNameMatcher = nonNull();
    return this;
  }

  public SpanMatcher withServiceName(String serviceName) {
    this.serviceNameMatcher = is(serviceName);
    return this;
  }

  public SpanMatcher withOperationName(String operationName) {
    this.operationNameMatcher = is(operationName);
    return this;
  }

  public SpanMatcher operationNameMatching(Pattern pattern) {
    this.operationNameMatcher = matches(pattern);
    return this;
  }

  public SpanMatcher withResourceName(String resourceName) {
    this.resourceNameMatcher = is(resourceName);
    return this;
  }

  public SpanMatcher resourceNameMatching(Pattern pattern) {
    this.resourceNameMatcher = matches(pattern);
    return this;
  }

  public SpanMatcher resourceNameMatching(Predicate<CharSequence> validator) {
    this.resourceNameMatcher = validates(validator);
    return this;
  }

  public SpanMatcher durationShorterThan(Duration duration) {
    this.durationMatcher = validates(d -> d.compareTo(duration) < 0);
    return this;
  }

  public SpanMatcher durationLongerThan(Duration duration) {
    this.durationMatcher = validates(d -> d.compareTo(duration) > 0);
    return this;
  }

  public SpanMatcher durationMatching(Predicate<Duration> validator) {
    this.durationMatcher = validates(validator);
    return this;
  }

  public void assertSpan(DDSpan span, DDSpan previousSpan) {
    // Apply parent id matcher from the previous span
    if (this.parentIdMatcher == CHILD_OF_PREVIOUS_MATCHER) {
      this.parentIdMatcher = is(previousSpan.getSpanId());
    }
    // Assert span values
    assertValue(this.idMatcher, span.getSpanId(), "Expected identifier");
    assertValue(this.parentIdMatcher, span.getParentId(), "Expected parent identifier");
    assertValue(this.serviceNameMatcher, span.getServiceName(), "Expected service name");
    assertValue(this.operationNameMatcher, span.getOperationName(), "Expected operation name");
    assertValue(this.resourceNameMatcher, span.getResourceName(), "Expected resource name");
    assertValue(
        this.durationMatcher, Duration.of(span.getDurationNano(), NANOS), "Expected duration");
    // TODO Add more values to test (tags, links, ...)
  }

  private <T> void assertValue(Matcher<T> matcher, T value, String message) {
    if (matcher != null && !matcher.test(value)) {
      Optional<T> expected = matcher.expected();
      if (expected.isPresent()) {
        throw new AssertionFailedError(message + ". " + matcher.message(), expected.get(), value);
      } else {
        throw new AssertionFailedError(message + ": " + value + ". " + matcher.message());
      }
    }
  }
}
