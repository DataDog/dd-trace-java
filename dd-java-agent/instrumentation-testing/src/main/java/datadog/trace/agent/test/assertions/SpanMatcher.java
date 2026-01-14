package datadog.trace.agent.test.assertions;

import static datadog.trace.agent.test.assertions.Matchers.assertValue;
import static datadog.trace.agent.test.assertions.Matchers.is;
import static datadog.trace.agent.test.assertions.Matchers.isFalse;
import static datadog.trace.agent.test.assertions.Matchers.isNonNull;
import static datadog.trace.agent.test.assertions.Matchers.isNull;
import static datadog.trace.agent.test.assertions.Matchers.isTrue;
import static datadog.trace.agent.test.assertions.Matchers.matches;
import static datadog.trace.agent.test.assertions.Matchers.validates;
import static datadog.trace.core.DDSpanAccessor.spanLinks;
import static java.time.Duration.ofNanos;

import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.core.DDSpan;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.opentest4j.AssertionFailedError;

/**
 * The class is a helper class to verify span attributes.
 *
 * <p>To get a {@code SpanMatcher}, use the static factory methods {@link #span()} and use it as
 * fluent builder to define the span matching constraints.
 *
 * <p>Span matching constraints includes:
 *
 * <ul>
 *   <li>span identifier with {@link #id(long)}, {@link #root()}, {@link #childOf(long)} and {@link
 *       #childOfPrevious()}
 *   <li>span service name with {@link #serviceName(String)} and {@link #serviceNameDefined()}
 *   <li>span operation name with {@link #operationName(String)} and {@link #operationName(Pattern)}
 *   <li>span resource name with {@link #resourceName(String)}, {@link #resourceName(Pattern)}, and
 *       {@link #resourceName(Predicate)}
 *   <li>span duration with {@link #durationShorterThan(Duration)} and {@link
 *       #durationLongerThan(Duration)}
 *   <li>span type with {@link #type(String)}
 *   <li>span error status with {@link #error()} and {@link #error(boolean)}
 *   <li>span tags with {@link #tags(TagsMatcher...)}
 *   <li>span links with {@link #links(SpanLinkMatcher...)}
 * </ul>
 */
public final class SpanMatcher {
  private Matcher<Long> idMatcher;
  private Matcher<Long> parentIdMatcher;
  private Matcher<String> serviceNameMatcher;
  private Matcher<CharSequence> operationNameMatcher;
  private Matcher<CharSequence> resourceNameMatcher;
  private Matcher<Duration> durationMatcher;
  private Matcher<String> typeMatcher;
  private Matcher<Boolean> errorMatcher;
  private TagsMatcher[] tagMatchers;
  private SpanLinkMatcher[] linkMatchers;

  private static final Matcher<Long> CHILD_OF_PREVIOUS_MATCHER = is(0L);

  private SpanMatcher() {
    this.serviceNameMatcher = validates(s -> s != null && !s.isEmpty());
    this.typeMatcher = isNull();
    this.errorMatcher = isFalse();
  }

  /**
   * Checks a span and its attributes.
   *
   * @return A new {@link SpanMatcher} instance to configure span matching constraints.
   */
  public static SpanMatcher span() {
    return new SpanMatcher();
  }

  /**
   * Checks the span identifier matches the given value.
   *
   * @param id The identifier of the span to match against.
   * @return The current {@link SpanMatcher} instance with the specified identifier constraint
   *     applied.
   */
  public SpanMatcher id(long id) {
    this.idMatcher = is(id);
    return this;
  }

  /**
   * Checks the span is a root span (i.e., a span with no parent).
   *
   * @return The current {@link SpanMatcher} instance with the root constraint applied.
   */
  public SpanMatcher root() {
    return childOf(0L);
  }

  /**
   * Checks the span is a direct child of the specified parent span.
   *
   * @param parentId The identifier of the parent span to match against.
   * @return The current {@link SpanMatcher} instance with the child-of constraint applied.
   */
  public SpanMatcher childOf(long parentId) {
    this.parentIdMatcher = is(parentId);
    return this;
  }

  /**
   * Checks the span is a direct child of the immediately preceding span in the trace.
   *
   * @return The current {@link SpanMatcher} instance with the child-of constraint applied.
   */
  public SpanMatcher childOfPrevious() {
    this.parentIdMatcher = CHILD_OF_PREVIOUS_MATCHER;
    return this;
  }

  /**
   * Checks the span has service name defined.
   *
   * @return The current {@link SpanMatcher} instance with a defined service name constraint
   *     applied.
   */
  public SpanMatcher serviceNameDefined() {
    this.serviceNameMatcher = isNonNull();
    return this;
  }

  /**
   * Checks the span service name matches the given value.
   *
   * @param serviceName The service name to match against.
   * @return The current {@link SpanMatcher} instance updated with the specified service name
   *     constraint.
   */
  public SpanMatcher serviceName(String serviceName) {
    this.serviceNameMatcher = is(serviceName);
    return this;
  }

  /**
   * Checks the span operation name matches the given value.
   *
   * @param operationName The operation name to match against.
   * @return The current {@link SpanMatcher} instance updated with the specified operation name
   *     constraint.
   */
  public SpanMatcher operationName(String operationName) {
    this.operationNameMatcher = is(operationName);
    return this;
  }

  /**
   * Checks the span operation name matches the provided regular expression pattern.
   *
   * @param pattern The {@link Pattern} to match the operation name against.
   * @return The current {@link SpanMatcher} instance updated with the specified operation name
   *     constraint.
   */
  public SpanMatcher operationName(Pattern pattern) {
    this.operationNameMatcher = matches(pattern);
    return this;
  }

  /**
   * Checks the span resource name matches the given value.
   *
   * @param resourceName The resource name to match against.
   * @return The current {@link SpanMatcher} instance updated with the specified resource name
   *     constraint.
   */
  public SpanMatcher resourceName(String resourceName) {
    this.resourceNameMatcher = is(resourceName);
    return this;
  }

  /**
   * Checks the span resource name matches the provided regular expression pattern.
   *
   * @param pattern The {@link Pattern} used to match the resource name against.
   * @return The current {@link SpanMatcher} instance updated with the specified resource name
   *     constraint.
   */
  public SpanMatcher resourceName(Pattern pattern) {
    this.resourceNameMatcher = matches(pattern);
    return this;
  }

  /**
   * Checks the span resource name matches the provided validator.
   *
   * @param validator The {@link Predicate} used to validate the resource name.
   * @return The current {@link SpanMatcher} instance updated with the specified resource name
   *     constraint.
   */
  public SpanMatcher resourceName(Predicate<CharSequence> validator) {
    this.resourceNameMatcher = validates(validator);
    return this;
  }

  /**
   * Checks the span duration is shorter than the given value.
   *
   * @param duration The maximum allowed duration.
   * @return The current {@link SpanMatcher} instance updated with the specified duration
   *     constraint.
   */
  public SpanMatcher durationShorterThan(Duration duration) {
    this.durationMatcher = validates(d -> d.compareTo(duration) < 0);
    return this;
  }

  /**
   * Checks the span duration is longer than the given value.
   *
   * @param duration The minimum allowed duration.
   * @return The current {@link SpanMatcher} instance updated with the specified duration
   *     constraint.
   */
  public SpanMatcher durationLongerThan(Duration duration) {
    this.durationMatcher = validates(d -> d.compareTo(duration) > 0);
    return this;
  }

  /**
   * Checks the span duration matches the given validator.
   *
   * @param validator The validator to check the span duration.
   * @return The current {@link SpanMatcher} instance updated with the specified duration
   *     constraint.
   */
  public SpanMatcher duration(Predicate<Duration> validator) {
    this.durationMatcher = validates(validator);
    return this;
  }

  /**
   * Checks the span type matches the given value.
   *
   * @param type The span type to match against.
   * @return The current {@link SpanMatcher} instance updated with the specified span type
   *     constraint.
   */
  public SpanMatcher type(String type) {
    this.typeMatcher = is(type);
    return this;
  }

  /**
   * Checks the span is an error span.
   *
   * @return The current {@link SpanMatcher} instance updated with the specified error constraint.
   */
  public SpanMatcher error() {
    return error(true);
  }

  /**
   * Checks the span error status matches the given value.
   *
   * @param errored The expected error status.
   * @return The current {@link SpanMatcher} instance updated with the specified error constraint.
   */
  public SpanMatcher error(boolean errored) {
    this.errorMatcher = errored ? isTrue() : isFalse();
    return this;
  }

  public SpanMatcher tags(TagsMatcher... matchers) {
    this.tagMatchers = matchers;
    return this;
  }

  /**
   * Checks the span links structure.
   *
   * @param matchers The {@link SpanLinkMatcher} to very the span links structure, one per link.
   * @return The current {@link SpanMatcher} instance updated with the specified span link
   *     constraints.
   */
  public SpanMatcher links(SpanLinkMatcher... matchers) {
    this.linkMatchers = matchers;
    return this;
  }

  void assertSpan(DDSpan span, DDSpan previousSpan) {
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
    assertValue(this.durationMatcher, ofNanos(span.getDurationNano()), "Expected duration");
    assertValue(this.typeMatcher, span.getSpanType(), "Expected span type");
    assertValue(this.errorMatcher, span.isError(), "Expected error status");
    assertSpanTags(span.getTags());
    assertSpanLinks(spanLinks(span));
  }

  private void assertSpanTags(TagMap tags) {
    // Check if tags should be asserted at all
    if (this.tagMatchers == null) {
      return;
    }
    // Collect all matchers
    Map<String, Matcher<?>> matchers = new HashMap<>();
    for (TagsMatcher tagMatcher : this.tagMatchers) {
      matchers.putAll(tagMatcher.tagMatchers);
    }
    // Assert all tags
    List<String> uncheckedTagNames = new ArrayList<>();
    tags.forEach(
        (key, value) -> {
          Matcher<Object> matcher = (Matcher) matchers.remove(key);
          if (matcher == null) {
            uncheckedTagNames.add(key);
          } else {
            assertValue(matcher, value, "Unexpected " + key + " tag value.");
          }
        });
    // Remove matchers that accept missing tags
    Collection<Matcher<?>> values = matchers.values();
    values.removeIf(matcher -> matcher instanceof Any);
    values.removeIf(matcher -> matcher instanceof IsNull);
    // Fails if any tags are missing
    if (!matchers.isEmpty()) {
      throw new AssertionFailedError("Missing tags: " + String.join(", ", matchers.keySet()));
    }
    // Fails if any unexpected tags are present
    if (!uncheckedTagNames.isEmpty()) {
      throw new AssertionFailedError("Unexpected tags: " + String.join(", ", uncheckedTagNames));
    }
  }

  /*
   * Right now, it's expecting to have as many matchers as links, and in the same order.
   * It might evolve into partial link collection testing, matching links using TID/SIP.
   */
  private void assertSpanLinks(List<AgentSpanLink> links) {
    int linkCount = links == null ? 0 : links.size();
    int expectedLinkCount = this.linkMatchers == null ? 0 : this.linkMatchers.length;
    if (linkCount != expectedLinkCount) {
      throw new AssertionFailedError("Unexpected span link count", expectedLinkCount, linkCount);
    }
    for (int i = 0; i < expectedLinkCount; i++) {
      SpanLinkMatcher linkMatcher = this.linkMatchers[expectedLinkCount];
      AgentSpanLink link = links.get(i);
      linkMatcher.assertLink(link);
    }
  }
}
