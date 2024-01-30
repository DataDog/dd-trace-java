package datadog.trace.core.tagprocessor;

import datadog.trace.api.DDTags;
import datadog.trace.api.naming.ServiceNaming;
import datadog.trace.core.DDSpanContext;
import java.util.Map;
import javax.annotation.Nonnull;

public class BaseServiceAdder implements TagsPostProcessor {
  private final ServiceNaming serviceNaming;

  public BaseServiceAdder(@Nonnull final ServiceNaming serviceNaming) {
    this.serviceNaming = serviceNaming;
  }

  @Override
  public Map<String, Object> processTags(Map<String, Object> unsafeTags) {
    // we are only able to do something if the span service name is known
    return unsafeTags;
  }

  @Override
  public Map<String, Object> processTagsWithContext(
      Map<String, Object> unsafeTags, DDSpanContext spanContext) {
    if (!serviceNaming.getCurrent().toString().equalsIgnoreCase(spanContext.getServiceName())) {
      unsafeTags.put(DDTags.BASE_SERVICE, serviceNaming.getCurrent());
    }
    return unsafeTags;
  }
}
