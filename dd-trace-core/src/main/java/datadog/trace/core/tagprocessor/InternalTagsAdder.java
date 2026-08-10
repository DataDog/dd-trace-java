package datadog.trace.core.tagprocessor;

import static datadog.trace.bootstrap.instrumentation.api.Tags.VERSION;

import datadog.trace.api.DDTags;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AppendableSpanLinks;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.DDSpanContext;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class InternalTagsAdder extends TagsPostProcessor {
  private final UTF8BytesString ddService;

  // Prebuilt once to avoid per-span Entry allocation.
  private final TagMap.Entry baseServiceEntry;
  @Nullable private final TagMap.Entry versionEntry;

  public InternalTagsAdder(@Nonnull final String ddService, @Nullable final String version) {
    this.ddService = UTF8BytesString.create(ddService);
    this.baseServiceEntry = TagMap.Entry.create(DDTags.BASE_SERVICE, this.ddService);
    this.versionEntry =
        version != null && !version.isEmpty()
            ? TagMap.Entry.create(VERSION, UTF8BytesString.create(version))
            : null;
  }

  @Override
  public void processTags(
      TagMap unsafeTags, DDSpanContext spanContext, AppendableSpanLinks spanLinks) {
    if (spanContext == null) {
      return;
    }

    if (!ddService.toString().equalsIgnoreCase(spanContext.getServiceName())) {
      if (baseServiceEntry != null) {
        // service name != DD_SERVICE
        unsafeTags.set(baseServiceEntry);
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
