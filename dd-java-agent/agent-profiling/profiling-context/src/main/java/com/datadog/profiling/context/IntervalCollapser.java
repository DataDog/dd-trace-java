package com.datadog.profiling.context;

final class IntervalCollapser {
  interface Callback {
    Callback NULL = (intervalStart, intervalDuration) -> {};

    void accept(long intervalStart, long intervalDuration);
  }

  private boolean clicker = false;
  private long intervalStart = Long.MAX_VALUE;
  private long intervalEnd = Long.MIN_VALUE;
  private final Callback callback;
  private final long threshold;
  private long currentTimestamp;

  IntervalCollapser(long threshold, long timestamp, Callback callback) {
    this.threshold = threshold;
    this.currentTimestamp = timestamp;
    this.callback = callback != null ? callback : Callback.NULL;
  }

  long processDelta(long delta) {
    currentTimestamp += delta;
    if (clicker) {
      // interval end
      intervalEnd = currentTimestamp;
      clicker = false;
    } else {
      // interval start
      if (intervalStart == Long.MAX_VALUE) {
        intervalStart = currentTimestamp;
      } else if (threshold > -1 && delta < threshold) {
        intervalEnd = Long.MIN_VALUE;
      } else {
        callback.accept(intervalStart, intervalEnd - intervalStart);
        intervalStart = currentTimestamp;
        intervalEnd = Long.MIN_VALUE;
      }
      clicker = true;
    }
    return currentTimestamp;
  }

  void finish() {
    if (intervalStart != Long.MAX_VALUE && intervalEnd != Long.MIN_VALUE) {
      callback.accept(intervalStart, intervalEnd - intervalStart);
    }
  }
}
