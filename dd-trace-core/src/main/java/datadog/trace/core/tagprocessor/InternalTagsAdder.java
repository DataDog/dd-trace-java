package datadog.trace.core.tagprocessor;

import static datadog.trace.bootstrap.instrumentation.api.Tags.VERSION;

import datadog.trace.api.DDTags;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.DDSpanContext;
import java.util.List;
import javax.annotation.Nullable;

public final class InternalTagsAdder extends TagsPostProcessor {
  private final String ddService;
  private final TagMap.Entry ddServiceEntry;
  private final TagMap.Entry versionEntry;

  public InternalTagsAdder(@Nullable final String ddService, @Nullable final String version) {
    this.ddService = ddService;
    this.ddServiceEntry =
        ddService != null
            ? TagMap.Entry.create(DDTags.BASE_SERVICE, UTF8BytesString.create(ddService))
            : null;
    this.versionEntry =
        version != null && !version.isEmpty()
            ? TagMap.Entry.create(VERSION, UTF8BytesString.create(version))
            : null;
  }

  @Override
  public void processTags(
      TagMap unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
    if (spanContext == null || ddService == null) {
      return;
    }

    if (!ddService.toString().equalsIgnoreCase(spanContext.getServiceName())) {
      // service name !=  DD_SERVICE
      unsafeTags.set(ddServiceEntry);
    } else {
      // as per config consistency, the version tag is added across tracers only if
      // the service name is DD_SERVICE and version  tag is not manually set
      if (versionEntry != null && !unsafeTags.containsKey(VERSION)) {
        unsafeTags.set(versionEntry);
      }
    }
  }
}
