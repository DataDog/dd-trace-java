package datadog.trace.core.tagprocessor;

import static datadog.trace.bootstrap.instrumentation.api.Tags.VERSION;

import datadog.trace.api.KnownTagIds;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AppendableSpanLinks;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.DDSpanContext;
import javax.annotation.Nullable;

public final class InternalTagsAdder extends TagsPostProcessor {
  private final UTF8BytesString ddService;

  // base.service / version are fixed for the life of the tracer, so their TagMap.Entry objects are
  // pre-built once and shared across every span (Entry is immutable and safe to share between
  // maps).
  // The entries are tag-id-bearing (KnownTagIds), so they also land in their positional slot. null
  // when the corresponding value is absent/empty. See PR #11555 for the string-keyed precursor.
  @Nullable private final TagMap.Entry baseServiceEntry;
  @Nullable private final TagMap.Entry versionEntry;

  public InternalTagsAdder(@Nullable final String ddService, @Nullable final String version) {
    this.ddService = ddService != null ? UTF8BytesString.create(ddService) : null;
    this.baseServiceEntry = TagMap.Entry.create(KnownTagIds.BASE_SERVICE, this.ddService);
    this.versionEntry =
        version != null && !version.isEmpty()
            ? TagMap.Entry.create(KnownTagIds.VERSION, UTF8BytesString.create(version))
            : null;
  }

  @Override
  public void processTags(
      TagMap unsafeTags, DDSpanContext spanContext, AppendableSpanLinks spanLinks) {
    if (spanContext == null || ddService == null || ddService.length() == 0) {
      return;
    }

    if (!ddService.toString().equalsIgnoreCase(spanContext.getServiceName())) {
      // service name != DD_SERVICE
      unsafeTags.set(baseServiceEntry);
    } else {
      // as per config consistency, the version tag is added across tracers only if
      // the service name is DD_SERVICE and version tag is not manually set
      if (versionEntry != null && !unsafeTags.containsKey(VERSION)) {
        unsafeTags.set(versionEntry);
      }
    }
  }
}
