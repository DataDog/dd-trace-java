package datadog.trace.core.tagprocessor;

import datadog.trace.api.TagStore;
import datadog.trace.bootstrap.instrumentation.api.AppendableSpanLinks;
import datadog.trace.core.DDSpanContext;

/**
 * A tag post-processor run on the application thread as a span finishes. It works on the span's
 * tags as stored, through a {@link TagStore}, rather than a {@code TagMap} the span would have to
 * build for it. The span passes itself as that store, which allocates nothing.
 */
public abstract class EagerTagsPostProcessor {
  /**
   * Processes the span's tags.
   *
   * @param unsafeTags the span's tags as stored, to read and write; the caller holds the span's tag
   *     lock
   * @param spanContext the span, for its other properties
   * @param spanLinks the span's links
   */
  public abstract void processTags(
      TagStore unsafeTags, DDSpanContext spanContext, AppendableSpanLinks spanLinks);
}
