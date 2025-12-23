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

/*
 * TODO: Dev notes
 * - inconsistency with "<field><matching-rule>()" vs "has<field>()"
 * - hasServiceName / withError / withoutError
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

  public static SpanMatcher span() {
    return new SpanMatcher();
  }

  public SpanMatcher id(long id) {
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
    this.serviceNameMatcher = isNonNull();
    return this;
  }

  public SpanMatcher serviceName(String serviceName) {
    this.serviceNameMatcher = is(serviceName);
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

  public SpanMatcher durationShorterThan(Duration duration) {
    this.durationMatcher = validates(d -> d.compareTo(duration) < 0);
    return this;
  }

  public SpanMatcher durationLongerThan(Duration duration) {
    this.durationMatcher = validates(d -> d.compareTo(duration) > 0);
    return this;
  }

  public SpanMatcher duration(Predicate<Duration> validator) {
    this.durationMatcher = validates(validator);
    return this;
  }

  public SpanMatcher type(String type) {
    this.typeMatcher = is(type);
    return this;
  }

  public SpanMatcher withError() {
    this.errorMatcher = isTrue();
    return this;
  }

  public SpanMatcher withoutError() {
    this.errorMatcher = isFalse();
    return this;
  }

  public SpanMatcher tags(TagsMatcher... matchers) {
    this.tagMatchers = matchers;
    return this;
  }

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
