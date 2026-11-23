package datadog.trace.core.otlp.trace;

import static datadog.trace.core.DDSpanContext.SPAN_SAMPLING_MECHANISM_TAG;

import datadog.trace.api.Config;
import datadog.trace.api.TagMap;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.DDSpan;
import datadog.trace.core.otlp.common.OtlpPayload;
import java.util.List;

/** Collects traces ready for export. */
public abstract class OtlpTraceCollector {

  /** The startup global tags, for a trace whose config snapshot can't be read. */
  private static final TagMap CONFIG_GLOBAL_TAGS =
      TagMap.fromMapImmutable(Config.get().getGlobalTags());

  /** The global tags of the resource being collected; null until the payload's first span. */
  protected TagMap payloadGlobalTags;

  /** Adds spans from the given trace to the collector. */
  public abstract void addTrace(List<? extends CoreSpan<?>> spans);

  /** Collects all spans added since the last collection. */
  public abstract OtlpPayload collectTraces();

  /** Returns the number of bytes buffered since the last collection. */
  public abstract int sizeInBytes();

  protected final boolean shouldExport(CoreSpan<?> span) {
    return span.samplingPriority() > 0 // trace-level sampling priority
        || span.getTag(SPAN_SAMPLING_MECHANISM_TAG) != null; // span-level sampling priority
  }

  /**
   * The trace's global tags, shared by all its spans; the startup global tags when its config
   * snapshot can't be read.
   */
  protected static TagMap globalTagsOf(List<? extends CoreSpan<?>> spans) {
    CoreSpan<?> first = spans.isEmpty() ? null : spans.get(0);
    return first instanceof DDSpan
        ? ((DDSpan) first).spanContext().getGlobalTags()
        : CONFIG_GLOBAL_TAGS;
  }
}
