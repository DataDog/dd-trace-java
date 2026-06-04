package datadog.trace.core.tagprocessor;

import static datadog.trace.bootstrap.instrumentation.api.Tags.VERSION;

import datadog.trace.api.DDTags;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AppendableSpanLinks;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.DDSpanContext;
import javax.annotation.Nullable;

public final class InternalTagsAdder extends TagsPostProcessor {
  // ddService drives the guard and the service-name comparison; kept even when empty so behavior
  // matches the prior implementation exactly (an explicitly-empty DD_SERVICE is a valid, if
  // degenerate, config -- see getStringExcludingSource, which passes "" through, not the default).
  private final UTF8BytesString ddService;

  // base.service / version values are fixed for the life of the tracer, and TagMap.Entry objects
  // are safe to share across maps (the OptimizedTagMap collision design relies on it), so the
  // entries are built once and reused on the hot path instead of allocating one per span.
  // baseServiceEntry is null when ddService is null OR empty (Entry.create rejects empty values);
  // the empty case falls back to set(tag, value) below to preserve byte-identical behavior.
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
