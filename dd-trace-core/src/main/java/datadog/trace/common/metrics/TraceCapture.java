package datadog.trace.common.metrics;

import datadog.trace.core.CoreSpan;
import java.util.List;

/**
 * A finished trace posted to the aggregator thread, which reads the stats fields from its spans
 * itself instead of the producer capturing a {@link SpanSnapshot} per span. The producer keeps only
 * what it needs synchronously: which spans count, and whether any of them errored, for {@code
 * forceKeep}.
 *
 * <p>The trace stays reachable until the aggregator thread drains it, including a trace the sampler
 * then drops. The spans are finished, so the aggregator reads them under each span's tag lock to
 * stay safe against a late tag write or a concurrent serializer.
 */
final class TraceCapture implements InboxItem {
  final List<? extends CoreSpan<?>> trace;

  /** Spans {@code [0, limit)} are considered: an ignored resource ends the trace there. */
  final int limit;

  /** The peer-tag schema the producer read for this trace, as {@code publish} did per span. */
  final PeerTagSchema peerTagSchema;

  TraceCapture(List<? extends CoreSpan<?>> trace, int limit, PeerTagSchema peerTagSchema) {
    this.trace = trace;
    this.limit = limit;
    this.peerTagSchema = peerTagSchema;
  }

  /** Reads a span's stats fields; implemented by the aggregator that posts these. */
  interface Capturer {
    /** Whether a span is counted, as the producer decided: the same test, so the same answer. */
    boolean counts(CoreSpan<?> span, boolean isTopLevel);

    /** Fills {@code into} from {@code span}. Called under the span's tag lock. */
    void capture(
        CoreSpan<?> span, boolean isTopLevel, PeerTagSchema peerTagSchema, SpanSnapshot into);
  }
}
