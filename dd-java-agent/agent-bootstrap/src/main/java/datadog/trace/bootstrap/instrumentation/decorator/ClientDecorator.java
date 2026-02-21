package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;

public abstract class ClientDecorator extends BaseDecorator {
  // Deliberately not volatile, reading a stale null and creating an extra Entry is safe
  private TagMap.Entry cachedSpanKindEntry = null;

  protected abstract String service();

  /** Caches span kind entry to reduce allocation */
  private final TagMap.Entry spanKindEntry() {
    TagMap.Entry kindEntry = cachedSpanKindEntry;
    if (kindEntry == null) {
      cachedSpanKindEntry = kindEntry = TagMap.Entry.create(Tags.SPAN_KIND, spanKind());
    }
    return kindEntry;
  }

  protected String spanKind() {
    return Tags.SPAN_KIND_CLIENT;
  }

  @Override
  public AgentSpan afterStart(final AgentSpan span) {
    final String service = service();
    if (service != null) {
      span.setServiceName(service, component());
    }
    span.setTag(spanKindEntry());

    // Generate metrics for all client spans.
    span.setMeasured(true);
    return super.afterStart(span);
  }
}
