package datadog.trace.core;

import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.WritableSpanLinks;
import java.util.List;

public interface SpanLinkAccessor extends WritableSpanLinks {
  public List<? extends AgentSpanLink> getLinks();
}
