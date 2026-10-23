package datadog.trace.core.tagprocessor;

import datadog.trace.api.SpanStorage;
import datadog.trace.bootstrap.instrumentation.api.AppendableSpanLinks;
import datadog.trace.core.DDSpanContext;

/**
 * A tag post-processor run on the application thread as a span finishes. It works on the span's
 * {@link SpanStorage} rather than a {@code TagMap}, so the span needn't build one for it.
 */
public abstract class EagerTagsPostProcessor {
  /**
   * Processes the span's tags.
   *
   * @param contents the span's contents to read and write; the caller holds the span's tag lock
   * @param spanContext the span, for its other properties
   * @param spanLinks the span's links
   */
  public abstract void processTags(
      SpanStorage storage, DDSpanContext spanContext, AppendableSpanLinks spanLinks);
}
