package datadog.trace.core.tagprocessor;

import datadog.trace.api.DDTags;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.DDSpanContext;
import java.util.List;
import javax.annotation.Nullable;

public final class BaseServiceAdder extends TagsPostProcessor {
  private final UTF8BytesString ddService;

  public BaseServiceAdder(@Nullable final String ddService) {
    this.ddService = ddService != null ? UTF8BytesString.create(ddService) : null;
  }

  @Override
  public void processTags(
      TagMap unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
    if (ddService != null
        && spanContext != null
        && !ddService.toString().equalsIgnoreCase(spanContext.getServiceName())) {
      unsafeTags.put(DDTags.BASE_SERVICE, ddService);
      unsafeTags.remove("version");
    }
  }
}
