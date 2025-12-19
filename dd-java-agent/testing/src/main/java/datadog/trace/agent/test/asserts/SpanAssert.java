package datadog.trace.agent.test.asserts;

import static datadog.trace.agent.test.asserts.LinksAssert.assertLinks;
import static datadog.trace.agent.test.asserts.TagsAssert.assertTags;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.core.DDSpan;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class SpanAssert {

  private final DDSpan span;
  private final DDSpan previous;
  private boolean checkLinks = true;
  private final Map<String, Boolean> checked = new HashMap<>();

  private SpanAssert(DDSpan span, DDSpan previous) {
    this.span = span;
    this.previous = previous;
  }

  public static void assertSpan(DDSpan span, Consumer<SpanAssert> spec, DDSpan previous) {

    SpanAssert asserter = new SpanAssert(span, previous);
    asserter.assertSpan(spec);
  }

  public static void assertSpan(DDSpan span, Consumer<SpanAssert> spec) {

    assertSpan(span, spec, null);
  }

  public void assertSpan(Consumer<SpanAssert> spec) {
    spec.accept(this);
    assertDefaults();
  }

  public void assertSpanNameContains(String spanName, String... shouldContainArr) {
    for (String shouldContain : shouldContainArr) {
      if (!spanName.contains(shouldContain)) {
        throw new AssertionError(
            "Span name \"" + spanName + "\" does not contain \"" + shouldContain + "\"");
      }
    }
  }

  public void hasServiceName() {
    if (span.getServiceName() == null || span.getServiceName().isEmpty()) {
      throw new AssertionError("Service name is null or empty");
    }
  }

  public void serviceName(String name) {
    if (!name.equals(span.getServiceName())) {
      throw new AssertionError(
          "Expected serviceName \"" + name + "\" but was \"" + span.getServiceName() + "\"");
    }
    checked.put("serviceName", true);
  }

  public void operationName(String name) {
    String op = span.getOperationName().toString();
    if (!op.equals(name)) {
      throw new AssertionError("Expected operationName \"" + name + "\" but was \"" + op + "\"");
    }
    checked.put("operationName", true);
  }

  public void operationNameMatches(java.util.function.Predicate<String> eval) {
    String op = span.getOperationName().toString();
    if (!eval.test(op)) {
      throw new AssertionError("operationName predicate did not match: " + op);
    }
    checked.put("operationName", true);
  }

  public void operationNameContains(String... operationNameParts) {
    assertSpanNameContains(span.getOperationName().toString(), operationNameParts);
    checked.put("operationName", true);
  }

  public void resourceName(Pattern pattern) {
    String res = span.getResourceName().toString();
    if (!pattern.matcher(res).matches()) {
      throw new AssertionError("Resource name \"" + res + "\" does not match pattern " + pattern);
    }
    checked.put("resourceName", true);
  }

  public void resourceName(String name) {
    String res = span.getResourceName().toString();
    if (!res.equals(name)) {
      throw new AssertionError("Expected resourceName \"" + name + "\" but was \"" + res + "\"");
    }
    checked.put("resourceName", true);
  }

  public void resourceNameMatches(java.util.function.Predicate<String> eval) {
    String res = span.getResourceName().toString();
    if (!eval.test(res)) {
      throw new AssertionError("resourceName predicate did not match: " + res);
    }
    checked.put("resourceName", true);
  }

  public void resourceNameContains(String... resourceNameParts) {
    assertSpanNameContains(span.getResourceName().toString(), resourceNameParts);
    checked.put("resourceName", true);
  }

  public void duration(java.util.function.Predicate<Long> eval) {
    long duration = span.getDurationNano();
    if (!eval.test(duration)) {
      throw new AssertionError("duration predicate did not match: " + duration);
    }
    checked.put("duration", true);
  }

  public void spanType(String type) {
    if (span.getSpanType() == null) {
      if (type != null) {
        throw new AssertionError("Expected spanType \"" + type + "\" but was null");
      }
    } else {
      String actual = span.getSpanType().toString();
      if (type == null || !actual.equals(type)) {
        throw new AssertionError("Expected spanType \"" + type + "\" but was \"" + actual + "\"");
      }
    }
    if (span.getTags().get("span.type") != null) {
      throw new AssertionError("span.type tag should be null");
    }
    checked.put("spanType", true);
  }

  public void parent() {
    if (span.getParentId() != DDSpanId.ZERO) {
      throw new AssertionError("Expected root span but parentId=" + span.getParentId());
    }
    checked.put("parentId", true);
  }

  public void parentSpanId(BigInteger parentId) {
    long id = parentId == null ? 0 : DDSpanId.from(String.valueOf(parentId));
    if (span.getParentId() != id) {
      throw new AssertionError("Expected parentId " + id + " but was " + span.getParentId());
    }
    checked.put("parentId", true);
  }

  public void traceId(BigInteger traceId) {
    traceDDId(traceId != null ? DDTraceId.from(String.valueOf(traceId)) : null);
  }

  public void traceDDId(DDTraceId traceId) {
    if (!(traceId == null ? span.getTraceId() == null : traceId.equals(span.getTraceId()))) {
      throw new AssertionError("Expected traceId " + traceId + " but was " + span.getTraceId());
    }
    checked.put("traceId", true);
  }

  public void childOf(DDSpan parent) {
    if (span.getParentId() != parent.getSpanId()) {
      throw new AssertionError(
          "Expected parentId " + parent.getSpanId() + " but was " + span.getParentId());
    }
    checked.put("parentId", true);

    if (!span.getTraceId().equals(parent.getTraceId())) {
      throw new AssertionError(
          "Expected traceId " + parent.getTraceId() + " but was " + span.getTraceId());
    }
    checked.put("traceId", true);
  }

  public void childOfPrevious() {
    if (previous == null) {
      throw new AssertionError("Previous span is null");
    }
    childOf(previous);
  }

  public void threadNameStartsWith(String threadName) {
    Object tn = span.getTags().get("thread.name");
    if (!(tn instanceof String) || !((String) tn).startsWith(threadName)) {
      throw new AssertionError(
          "Thread name \"" + tn + "\" does not start with \"" + threadName + "\"");
    }
  }

  public void notChildOf(DDSpan parent) {
    if (parent.getSpanId() == span.getParentId()) {
      throw new AssertionError("Span is child of given parent");
    }
    if (parent.getTraceId().equals(span.getTraceId())) {
      throw new AssertionError("Span is in same trace as given parent");
    }
  }

  public void errored(boolean errored) {
    if (span.isError() != errored) {
      throw new AssertionError("Expected errored=" + errored + " but was " + span.isError());
    }
    checked.put("errored", true);
  }

  public void topLevel(boolean topLevel) {
    if (span.isTopLevel() != topLevel) {
      throw new AssertionError("Expected topLevel=" + topLevel + " but was " + span.isTopLevel());
    }
    checked.put("topLevel", true);
  }

  public void measured(boolean measured) {
    if (span.isMeasured() != measured) {
      throw new AssertionError("Expected measured=" + measured + " but was " + span.isMeasured());
    }
    checked.put("measured", true);
  }

  private void assertDefaults() {
    if (!Boolean.TRUE.equals(checked.get("spanType"))) {
      spanType(null);
    }
    if (!Boolean.TRUE.equals(checked.get("errored"))) {
      errored(false);
    }
    if (checkLinks) {
      if (!Boolean.TRUE.equals(checked.get("links"))) {
        if (span.getTags().get("_dd.span_links") != null) {
          throw new AssertionError("_dd.span_links should be null");
        }
      }
    }
    hasServiceName();
  }

  public void tags(Consumer<TagsAssert> spec) {
    assertTags(span, spec);
  }

  public void tags(boolean checkAllTags, Consumer<TagsAssert> spec) {
    assertTags(span, spec, checkAllTags);
  }

  public void ignoreSpanLinks() {
    this.checkLinks = false;
  }

  public void links(Consumer<LinksAssert> spec) {
    checked.put("links", true);
    assertLinks(span, spec);
  }

  public void links(boolean checkAllLinks, Consumer<LinksAssert> spec) {
    checked.put("links", true);
    assertLinks(span, spec, checkAllLinks);
  }

  public DDSpan getSpan() {
    return span;
  }
}
