package datadog.test.agent.assertions;

import static datadog.test.agent.assertions.Matchers.is;
import static datadog.test.agent.assertions.Matchers.matches;
import static datadog.test.agent.assertions.Matchers.nonNull;
import static datadog.test.agent.assertions.Matchers.validates;

import datadog.test.agent.AgentSpan;
import org.opentest4j.AssertionFailedError;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public final class AgentSpanMatcher {
  private Matcher<String> serviceNameMatcher;
  private Matcher<String> operationNameMatcher;
  private Matcher<String> resourceNameMatcher;
  private Matcher<Duration> durationMatcher;

  private AgentSpanMatcher() {
  }

  public static AgentSpanMatcher span() {
    return new AgentSpanMatcher();
  }

  public AgentSpanMatcher hasServiceName() {
    this.serviceNameMatcher = nonNull();
    return this;
  }

  public AgentSpanMatcher withServiceName(String serviceName) {
    this.serviceNameMatcher = is(serviceName);
    return this;
  }

  public AgentSpanMatcher withOperationName(String operationName) {
    this.operationNameMatcher = is(operationName);
    return this;
  }

  public AgentSpanMatcher operationNameMatching(Pattern pattern) {
    this.operationNameMatcher = matches(pattern);
    return this;
  }

  public AgentSpanMatcher withResourceName(String resourceName) {
    this.resourceNameMatcher = is(resourceName);
    return this;
  }

  public AgentSpanMatcher resourceNameMatching(Pattern pattern) {
    this.resourceNameMatcher = matches(pattern);
    return this;
  }

  public AgentSpanMatcher resourceNameMatching(Predicate<String> validator) {
    this.resourceNameMatcher = validates(validator);
    return this;
  }

  public AgentSpanMatcher durationShorterThan(Duration duration) {
    this.durationMatcher = validates(d -> d.compareTo(duration) < 0);
    return this;
  }

  public AgentSpanMatcher durationLongerThan(Duration duration) {
    this.durationMatcher = validates(d -> d.compareTo(duration) > 0);
    return this;
  }

  public AgentSpanMatcher durationMatching(Predicate<Duration> validator) {
    this.durationMatcher = validates(validator);
    return this;
  }

  public void assertSpan(AgentSpan span, AgentSpan previousSpan) {
    assertValue(this.serviceNameMatcher, span.service(), "Expected service name");
    assertValue(this.operationNameMatcher, span.name(), "Expected operation name");
    assertValue(this.resourceNameMatcher, span.resource(), "Expected resource name");
    assertValue(this.durationMatcher, span.duration(), "Expected duration");
  }

  private <T> void assertValue(Matcher<T> matcher, T value, String message) {
    if (matcher != null && !matcher.test(value)) {
      Optional<T> expected = matcher.expected();
      if (expected.isPresent()) {
        throw new AssertionFailedError(
            message + ". " + this.durationMatcher.message(),
            expected.get(),
            value
        );
      } else {
        throw new AssertionFailedError(message + ": " + value + ". " + this.durationMatcher.message());
      }
    }
  }
}
