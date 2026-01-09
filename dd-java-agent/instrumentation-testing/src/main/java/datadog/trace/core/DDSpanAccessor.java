package datadog.trace.core;

import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import java.util.List;

/**
 * This class is a helper class to get access to package private span data that should not be
 * exposed as part of the public API.
 */
public final class DDSpanAccessor {
  private DDSpanAccessor() {}

  public static List<AgentSpanLink> spanLinks(DDSpan span) {
    return span.links;
  }
}
