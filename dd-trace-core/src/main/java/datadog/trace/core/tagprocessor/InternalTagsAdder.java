package datadog.trace.core.tagprocessor;

import static datadog.trace.bootstrap.instrumentation.api.Tags.VERSION;

import datadog.trace.api.DDTags;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AppendableSpanLinks;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.DDSpanContext;
import javax.annotation.Nullable;

public final class InternalTagsAdder extends TagsPostProcessor {
  // Pre-built once at construction and reused on every span. The base.service / version tags are
  // fixed for the life of the tracer, so reusing the same immutable TagMap.Entry (Entries are
  // safe to share across maps) avoids allocating a fresh Entry per span in processTags.
  private final TagMap.Entry baseServiceEntry;
  private final TagMap.Entry versionEntry;

  public InternalTagsAdder(@Nullable final String ddService, @Nullable final String version) {
    this.baseServiceEntry =
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
      TagMap unsafeTags, DDSpanContext spanContext, AppendableSpanLinks spanLinks) {
    if (spanContext == null || baseServiceEntry == null) {
      return;
    }

    if (!baseServiceEntry.stringValue().equalsIgnoreCase(spanContext.getServiceName())) {
      // service name !=  DD_SERVICE
      unsafeTags.set(baseServiceEntry);
    } else {
      // as per config consistency, the version tag is added across tracers only if
      // the service name is DD_SERVICE and version  tag is not manually set
      if (versionEntry != null && !unsafeTags.containsKey(VERSION)) {
        unsafeTags.set(versionEntry);
      }
    }
  }
}
