package datadog.trace.agent.test.asserts;

import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.SpanAttributes;
import datadog.trace.bootstrap.instrumentation.api.SpanLink;
import datadog.trace.core.DDSpan;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public class LinksAssert {

  private final List<AgentSpanLink> links;
  private final Set<AgentSpanLink> assertedLinks = new HashSet<>();

  private LinksAssert(DDSpan span) {
    // In Groovy, this accesses a package/protected field; here we assume a getter exists or
    // same-package access.
    this.links = getLinksFromSpan(span);
  }

  private static List<AgentSpanLink> getLinksFromSpan(DDSpan span) {
    try {
      Field linksField = span.getClass().getDeclaredField("links");
      linksField.setAccessible(true);
      return (List<AgentSpanLink>) linksField.get(span);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  public static void assertLinks(DDSpan span, Consumer<LinksAssert> spec, boolean checkAllLinks) {

    LinksAssert asserter = new LinksAssert(span);
    spec.accept(asserter);
    if (checkAllLinks) {
      asserter.assertLinksAllVerified();
    }
  }

  public static void assertLinks(DDSpan span, Consumer<LinksAssert> spec) {

    assertLinks(span, spec, true);
  }

  public void link(DDSpan linked, byte flags, SpanAttributes attributes, String traceState) {

    link(linked.context(), flags, attributes, traceState);
  }

  public void link(DDSpan linked) {
    link(linked, SpanLink.DEFAULT_FLAGS, SpanAttributes.EMPTY, "");
  }

  public void link(
      AgentSpanContext context, byte flags, SpanAttributes attributes, String traceState) {

    link(context.getTraceId(), context.getSpanId(), flags, attributes, traceState);
  }

  public void link(AgentSpanContext context) {
    link(context, SpanLink.DEFAULT_FLAGS, SpanAttributes.EMPTY, "");
  }

  public void link(
      DDTraceId traceId, long spanId, byte flags, SpanAttributes attributes, String traceState) {

    AgentSpanLink found = null;
    for (AgentSpanLink link : links) {
      if (link.spanId() == spanId && link.traceId().equals(traceId)) {
        found = link;
        break;
      }
    }

    if (found == null) {
      throw new AssertionError(
          "Expected link for traceId=" + traceId + " spanId=" + spanId + " not found");
    }
    if (found.traceFlags() != flags) {
      throw new AssertionError("Expected traceFlags=" + flags + " but was " + found.traceFlags());
    }
    if (!found.attributes().equals(attributes)) {
      throw new AssertionError(
          "Expected attributes=" + attributes + " but was " + found.attributes());
    }
    if (!found.traceState().equals(traceState)) {
      throw new AssertionError(
          "Expected traceState=\"" + traceState + "\" but was \"" + found.traceState() + "\"");
    }

    assertedLinks.add(found);
  }

  public void link(DDTraceId traceId, long spanId) {
    link(traceId, spanId, SpanLink.DEFAULT_FLAGS, SpanAttributes.EMPTY, "");
  }

  public void assertLinksAllVerified() {
    List<AgentSpanLink> remaining = new ArrayList<>(links);
    remaining.removeAll(assertedLinks);
    if (!remaining.isEmpty()) {
      throw new AssertionError("Not all links were verified. Remaining: " + remaining.size());
    }
  }
}
