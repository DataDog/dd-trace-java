package datadog.trace.core.tagprocessor;

import datadog.trace.api.DDTags;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.core.DDSpanContext;
import java.util.List;
import java.util.function.Supplier;

public final class RemoteHostnameAdder extends TagsPostProcessor {
  private final Supplier<String> hostnameSupplier;

  public RemoteHostnameAdder(Supplier<String> hostnameSupplier) {
    this.hostnameSupplier = hostnameSupplier;
  }

  @Override
  public void processTags(
      TagMap unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
    if (spanContext.getSpanId() == spanContext.getRootSpanId()) {
      unsafeTags.put(DDTags.TRACER_HOST, hostnameSupplier.get());
    }
  }
}
