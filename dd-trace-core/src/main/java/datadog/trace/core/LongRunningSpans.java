package datadog.trace.core;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;

/**
 * A {@link PendingTrace}'s spans when the long-running traces feature is enabled: running spans as
 * well as finished ones, since running spans of a long-running trace are written periodically too.
 * A write takes the finished spans and keeps the running ones.
 *
 * <p>Kept apart from the default path, where a trace holds only finished spans and hands them off
 * without this deque's per-span nodes and per-span polling (see {@link PendingTrace}).
 */
final class LongRunningSpans {
  private final ConcurrentLinkedDeque<DDSpan> spans = new ConcurrentLinkedDeque<>();

  /** Adds a span when it starts; it stays until a write after it finishes. */
  void add(DDSpan span) {
    spans.addFirst(span);
  }

  /** The most recently added span, or null. */
  DDSpan peek() {
    return spans.peek();
  }

  boolean isEmpty() {
    return spans.isEmpty();
  }

  /** The earliest finish time of a finished span, or {@code Long.MAX_VALUE} if none finished. */
  long oldestFinishedTime() {
    long oldest = Long.MAX_VALUE;
    for (DDSpan span : spans) {
      if (span.isFinished()) {
        oldest = Math.min(oldest, span.getStartTime() + span.getDurationNano());
      }
    }
    return oldest;
  }

  /**
   * Moves the finished spans to {@code trace} and keeps the running ones; with {@code
   * writeRunningSpans}, also adds the running spans to {@code trace}, versioned by how long they've
   * been running at {@code nowNano}.
   *
   * @return the number of finished spans moved
   */
  int drainTo(List<DDSpan> trace, boolean writeRunningSpans, long nowNano) {
    int completedSpans = 0;
    boolean runningSpanSeen = false;
    long firstRunningSpanID = 0;

    DDSpan span = spans.pollFirst();
    while (null != span) {
      if (runningSpanSeen && span.getSpanId() == firstRunningSpanID) {
        // we iterated on all spans as we circled back to the first running span
        spans.addFirst(span);
        break;
      }
      if (span.isFinished()) {
        trace.add(span);
        completedSpans++;
      } else {
        // keep the running span in the list
        spans.add(span);
        if (!runningSpanSeen) {
          runningSpanSeen = true;
          firstRunningSpanID = span.getSpanId();
        }
        if (writeRunningSpans) {
          span.setLongRunningVersion(
              (int) TimeUnit.NANOSECONDS.toMillis(nowNano - span.getStartTime()));
          trace.add(span);
        }
      }
      span = spans.pollFirst();
    }
    return completedSpans;
  }

  /** A live view of the spans. */
  Collection<DDSpan> spans() {
    return spans;
  }
}
