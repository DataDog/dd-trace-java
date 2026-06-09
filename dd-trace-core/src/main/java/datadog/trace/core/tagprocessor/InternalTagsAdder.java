package datadog.trace.core.tagprocessor;

import static datadog.trace.bootstrap.instrumentation.api.Tags.VERSION;

import datadog.trace.api.DDTags;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AppendableSpanLinks;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.DDSpanContext;
import javax.annotation.Nullable;

public final class InternalTagsAdder extends TagsPostProcessor {
  private final UTF8BytesString ddService;

  // Prebuilt once; null when ddService is null or empty (Entry.create rejects empty values).
  @Nullable private final TagMap.Entry baseServiceEntry;
  @Nullable private final TagMap.Entry versionEntry;

  public InternalTagsAdder(@Nullable final String ddService, @Nullable final String version) {
    this.ddService = ddService != null ? UTF8BytesString.create(ddService) : null;
    this.baseServiceEntry =
        this.ddService != null && this.ddService.length() > 0
            ? TagMap.Entry.create(DDTags.BASE_SERVICE, this.ddService)
            : null;
    this.versionEntry =
        version != null && !version.isEmpty()
            ? TagMap.Entry.create(VERSION, UTF8BytesString.create(version))
            : null;
  }

  @Override
  public void processTags(
      TagMap unsafeTags, DDSpanContext spanContext, AppendableSpanLinks spanLinks) {
    if (spanContext == null || ddService == null) {
      return;
    }

    if (!ddService.toString().equalsIgnoreCase(spanContext.getServiceName())) {
      // service name != DD_SERVICE
      if (baseServiceEntry != null) {
        unsafeTags.set(baseServiceEntry);
      } else {
        // Empty DD_SERVICE: no prebuilt entry exists; preserve the original behavior.
        unsafeTags.set(DDTags.BASE_SERVICE, ddService);
      }
    } else {
      // as per config consistency, the version tag is added across tracers only if
      // the service name is DD_SERVICE and version  tag is not manually set
      if (versionEntry != null && !unsafeTags.containsKey(VERSION)) {
        unsafeTags.set(versionEntry);
      }
    }
  }
}
