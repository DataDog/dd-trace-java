package datadog.trace.core;

import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.AppendableSpanLinks;
import java.util.List;

/**
 * Interface that provides the ability to add a span link and 
 * access all the span links as an unmodifiable list
 */
public interface SpanLinkAccessor extends AppendableSpanLinks {
  List<? extends AgentSpanLink> getLinks();
}
