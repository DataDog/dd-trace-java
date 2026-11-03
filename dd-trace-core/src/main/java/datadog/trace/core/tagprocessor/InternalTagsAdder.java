package datadog.trace.core.tagprocessor;

import static datadog.trace.api.KnownTags.DD_BASE_SERVICE_ID;
import static datadog.trace.api.KnownTags.VERSION_ID;

import datadog.trace.api.TagMap;
import datadog.trace.api.TagStore;
import datadog.trace.bootstrap.instrumentation.api.AppendableSpanLinks;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.DDSpanContext;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class InternalTagsAdder extends EagerTagsPostProcessor {
  private final UTF8BytesString ddService;

  // Prebuilt once to avoid per-span Entry allocation.
  private final TagMap.Entry baseServiceEntry;
  @Nullable private final TagMap.Entry versionEntry;

  public InternalTagsAdder(@Nonnull final String ddService, @Nullable final String version) {
    this.ddService = UTF8BytesString.create(ddService);
    this.baseServiceEntry = TagMap.Entry.create(DD_BASE_SERVICE_ID, this.ddService);
    this.versionEntry =
        version != null && !version.isEmpty()
            ? TagMap.Entry.create(VERSION_ID, UTF8BytesString.create(version))
            : null;
  }

  @Override
  public void processTags(
      TagStore unsafeTags, DDSpanContext spanContext, AppendableSpanLinks spanLinks) {
    if (spanContext == null) {
      return;
    }

    if (!ddService.toString().equalsIgnoreCase(spanContext.getServiceName())) {
      if (baseServiceEntry != null) {
        // service name != DD_SERVICE
        unsafeTags.store(baseServiceEntry);
      }
    } else {
      // as per config consistency, the version tag is added across tracers only if
      // the service name is DD_SERVICE and version  tag is not manually set
      if (versionEntry != null && unsafeTags.load(VERSION_ID) == null) {
        unsafeTags.store(versionEntry);
      }
    }
  }
}
