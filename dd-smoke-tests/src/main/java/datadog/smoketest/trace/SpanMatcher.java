package datadog.smoketest.trace;

import static datadog.trace.test.junit.utils.assertions.Matchers.assertValue;
import static datadog.trace.test.junit.utils.assertions.Matchers.is;
import static datadog.trace.test.junit.utils.assertions.Matchers.isFalse;
import static datadog.trace.test.junit.utils.assertions.Matchers.isTrue;
import static datadog.trace.test.junit.utils.assertions.Matchers.matches;
import static datadog.trace.test.junit.utils.assertions.Matchers.validates;
import static org.junit.jupiter.api.AssertionFailureBuilder.assertionFailure;

import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedSpanLink;
import datadog.trace.test.junit.utils.assertions.Matcher;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The class is a helper class to verify span attributes on the {@link DecodedSpan} model produced
 * by smoke backends.
 *
 * <p>To get a {@code SpanMatcher}, use the static factory method {@link #span()} and use it as a
 * fluent builder to define the span matching constraints.
 *
 * <p>Span matching constraints cover only what a span carries once serialized:
 *
 * <ul>
 *   <li>span service name with {@link #service(String)}
 *   <li>span operation name with {@link #operationName(String)} and {@link #operationName(Pattern)}
 *   <li>span resource name with {@link #resourceName(String)}, {@link #resourceName(Pattern)}, and
 *       {@link #resourceName(Predicate)}
 *   <li>span type with {@link #type(String)}
 *   <li>span error status with {@link #error(boolean)}
 *   <li>span parent linkage with {@link #root()}, {@link #childOf(long)}, {@link
 *       #childOfIndex(int)}, and {@link #childOfPrevious()}
 *   <li>span meta (string) tags with {@link #tag(String, Matcher)} and {@link #tag(String, String)}
 *   <li>span metrics (numeric) tags with {@link #metric(String, Matcher)}
 *   <li>span meta_struct (nested structured data) with {@link #metaStruct(String, Matcher)}
 *   <li>span links with {@link #links(SpanLinkMatcher...)}
 * </ul>
 */
public final class SpanMatcher {
  private Matcher<String> serviceMatcher;
  private Matcher<CharSequence> operationNameMatcher;
  private Matcher<CharSequence> resourceNameMatcher;
  private Matcher<String> typeMatcher;
  private Matcher<Boolean> errorMatcher;
  private Matcher<Long> parentIdMatcher;
  private int parentSpanIndex;
  private SpanLinkMatcher[] linkMatchers;
  private final Map<String, Matcher<String>> metaMatchers;
  private final Map<String, Matcher<Number>> metricMatchers;
  private final Map<String, Matcher<?>> metaStructMatchers;

  private static final Matcher<Long> CHILD_OF_PREVIOUS_MATCHER = is(0L);

  private SpanMatcher() {
    this.serviceMatcher = validates(s -> s != null && !s.isEmpty());
    this.typeMatcher = validates(s -> s == null || s.isEmpty());
    this.errorMatcher = isFalse();
    this.parentSpanIndex = -1;
    this.metaMatchers = new HashMap<>();
    this.metricMatchers = new HashMap<>();
    this.metaStructMatchers = new HashMap<>();
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
   * Checks the span service name matches the given value.
   *
   * @param service The service name to match against.
   * @return The current {@link SpanMatcher} instance updated with the specified service name
   *     constraint.
   */
  public SpanMatcher service(String service) {
    this.serviceMatcher = is(service);
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
   * Checks the span error status matches the given value.
   *
   * @param errored The expected error status.
   * @return The current {@link SpanMatcher} instance updated with the specified error constraint.
   */
  public SpanMatcher error(boolean errored) {
    this.errorMatcher = errored ? isTrue() : isFalse();
    return this;
  }

  /**
   * Checks the span is a root span (i.e., a span with no parent).
   *
   * @return The current {@link SpanMatcher} instance with the root constraint applied.
   */
  public SpanMatcher root() {
    this.parentIdMatcher = is(0L);
    this.parentSpanIndex = -1;
    return this;
  }

  /**
   * Checks the span is a direct child of the specified parent span.
   *
   * @param parentSpanId The identifier of the parent span to match against.
   * @return The current {@link SpanMatcher} instance with the child-of constraint applied.
   */
  public SpanMatcher childOf(long parentSpanId) {
    this.parentIdMatcher = is(parentSpanId);
    this.parentSpanIndex = -1;
    return this;
  }

  /**
   * Checks the span is a direct child of the span at the specified index in the trace.
   *
   * @param parentSpanIndex The index of the parent span in the trace.
   * @return The current {@link SpanMatcher} instance with the child-of constraint applied.
   */
  public SpanMatcher childOfIndex(int parentSpanIndex) {
    if (parentSpanIndex < 0) {
      throw new IllegalArgumentException("index must be >= 0");
    }
    this.parentIdMatcher = null;
    this.parentSpanIndex = parentSpanIndex;
    return this;
  }

  /**
   * Checks the span is a direct child of the immediately preceding span in the trace.
   *
   * @return The current {@link SpanMatcher} instance with the child-of constraint applied.
   */
  public SpanMatcher childOfPrevious() {
    this.parentIdMatcher = CHILD_OF_PREVIOUS_MATCHER;
    this.parentSpanIndex = -1;
    return this;
  }

  /**
   * Checks the span links, one {@link SpanLinkMatcher} per expected link, matched positionally and
   * count-exact. Call it with no argument to assert the span carries no link at all; not calling it
   * asserts nothing about links.
   *
   * @param matchers The matchers to verify the span links, one per expected link.
   * @return The current {@link SpanMatcher} instance updated with the specified span link
   *     constraints.
   */
  public SpanMatcher links(SpanLinkMatcher... matchers) {
    this.linkMatchers = matchers;
    return this;
  }

  /**
   * Checks the span meta (string) tag matches the given matcher.
   *
   * @param name The name of the meta tag to match against.
   * @param matcher The matcher to check the meta tag value.
   * @return The current {@link SpanMatcher} instance updated with the specified meta tag
   *     constraint.
   */
  public SpanMatcher tag(String name, Matcher<String> matcher) {
    this.metaMatchers.put(name, matcher);
    return this;
  }

  /**
   * Checks the span meta (string) tag matches the given value.
   *
   * @param name The name of the meta tag to match against.
   * @param value The expected meta tag value.
   * @return The current {@link SpanMatcher} instance updated with the specified meta tag
   *     constraint.
   */
  public SpanMatcher tag(String name, String value) {
    this.metaMatchers.put(name, is(value));
    return this;
  }

  /**
   * Checks the span metric (numeric) tag matches the given matcher.
   *
   * @param name The name of the metric tag to match against.
   * @param matcher The matcher to check the metric tag value.
   * @return The current {@link SpanMatcher} instance updated with the specified metric tag
   *     constraint.
   */
  public SpanMatcher metric(String name, Matcher<Number> matcher) {
    this.metricMatchers.put(name, matcher);
    return this;
  }

  /**
   * Checks the span meta_struct entry matches the given matcher.
   *
   * @param name The name of the meta_struct entry to match against.
   * @param matcher The matcher to check the meta_struct entry value.
   * @return The current {@link SpanMatcher} instance updated with the specified meta_struct
   *     constraint.
   */
  public SpanMatcher metaStruct(String name, Matcher<?> matcher) {
    this.metaStructMatchers.put(name, matcher);
    return this;
  }

  void assertSpan(List<DecodedSpan> trace, int spanIndex) {
    DecodedSpan span = trace.get(spanIndex);
    assertValue(this.serviceMatcher, span.getService(), "Unexpected service name");
    assertValue(this.operationNameMatcher, span.getName(), "Unexpected operation name");
    assertValue(this.resourceNameMatcher, span.getResource(), "Unexpected resource name");
    assertValue(this.typeMatcher, span.getType(), "Unexpected span type");
    assertValue(this.errorMatcher, span.getError() != 0, "Unexpected error status");
    assertValue(parentIdMatcher(trace, spanIndex), span.getParentId(), "Unexpected parent id");
    assertSpanTags(span.getMeta());
    assertSpanMetrics(span.getMetrics());
    assertSpanMetaStruct(span.getMetaStruct());
    assertSpanLinks(trace, span);
  }

  private Matcher<Long> parentIdMatcher(List<DecodedSpan> trace, int spanIndex) {
    if (this.parentSpanIndex >= 0) {
      return is(trace.get(this.parentSpanIndex).getSpanId());
    } else if (this.parentIdMatcher == CHILD_OF_PREVIOUS_MATCHER) {
      if (spanIndex == 0) {
        throw new IllegalStateException("Cannot use childOfPrevious() matcher on the first span");
      }
      return is(trace.get(spanIndex - 1).getSpanId());
    } else {
      return this.parentIdMatcher;
    }
  }

  private void assertSpanLinks(List<DecodedSpan> trace, DecodedSpan span) {
    if (this.linkMatchers == null) {
      return;
    }
    List<DecodedSpanLink> links = span.getLinks();
    if (links.size() != this.linkMatchers.length) {
      assertionFailure()
          .message("Unexpected span link count")
          .expected(this.linkMatchers.length)
          .actual(links.size())
          .buildAndThrow();
    }
    for (int i = 0; i < this.linkMatchers.length; i++) {
      this.linkMatchers[i].assertLink(trace, links.get(i), i);
    }
  }

  private void assertSpanTags(Map<String, String> meta) {
    for (Entry<String, Matcher<String>> entry : this.metaMatchers.entrySet()) {
      String key = entry.getKey();
      assertValue(entry.getValue(), meta.get(key), "Unexpected meta tag '" + key + "'");
    }
  }

  private void assertSpanMetrics(Map<String, Number> metrics) {
    for (Entry<String, Matcher<Number>> entry : this.metricMatchers.entrySet()) {
      assertValue(
          entry.getValue(),
          metrics.get(entry.getKey()),
          "Unexpected metric '" + entry.getKey() + "'");
    }
  }

  @SuppressWarnings("unchecked")
  private void assertSpanMetaStruct(Map<String, Object> metaStruct) {
    for (Entry<String, Matcher<?>> entry : this.metaStructMatchers.entrySet()) {
      String key = entry.getKey();
      assertValue(
          (Matcher<Object>) entry.getValue(),
          metaStruct.get(key),
          "Unexpected meta_struct '" + key + "'");
    }
  }
}
