package datadog.trace.core.tagprocessor;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.core.DDSpanContext;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class RemoteHostnameAdder implements TagsPostProcessor {
  private final Supplier<String> hostnameSupplier;

  public RemoteHostnameAdder(Supplier<String> hostnameSupplier) {
    this.hostnameSupplier = hostnameSupplier;
  }

  @Override
  public Map<String, Object> processTags(
      Map<String, Object> unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
    if (spanContext.getSpanId() == spanContext.getRootSpanId()) {
      unsafeTags.put(DDTags.TRACER_HOST, hostnameSupplier.get());
    }
    return unsafeTags;
  }
}
