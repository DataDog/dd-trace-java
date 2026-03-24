package datadog.trace.core.tagprocessor;

import static datadog.trace.bootstrap.instrumentation.api.Tags.VERSION;

import datadog.trace.api.DDTags;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.WritableSpanLinks;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.DDSpanContext;
import java.util.List;
import javax.annotation.Nullable;

public final class InternalTagsAdder extends TagsPostProcessor {
  private final UTF8BytesString ddService;
  private final UTF8BytesString version;

  public InternalTagsAdder(@Nullable final String ddService, @Nullable final String version) {
    this.ddService = ddService != null ? UTF8BytesString.create(ddService) : null;
    this.version = version != null && !version.isEmpty() ? UTF8BytesString.create(version) : null;
  }

  @Override
  public void processTags(
      TagMap unsafeTags, DDSpanContext spanContext, WritableSpanLinks spanLinks) {
    if (spanContext == null || ddService == null) {
      return;
    }

    if (!ddService.toString().equalsIgnoreCase(spanContext.getServiceName())) {
      // service name !=  DD_SERVICE
      unsafeTags.set(DDTags.BASE_SERVICE, ddService);
    } else {
      // as per config consistency, the version tag is added across tracers only if
      // the service name is DD_SERVICE and version  tag is not manually set
      if (version != null && !unsafeTags.containsKey(VERSION)) {
        unsafeTags.set(VERSION, version);
      }
    }
  }
}
