package datadog.trace.bootstrap.instrumentation.api;

import java.util.HashMap;
import java.util.Map;
import lombok.Getter;

// Not thread safe!
@Getter
public class SubTrace implements Comparable<SubTrace> {
  private final Pair<Class, String> key;
  private long durationSum = 0;
  private long exclusiveDurationSum = 0;
  private int count = 0;
  private int errorCount = 0;
  private boolean closed = false;

  public SubTrace(final Pair<Class, String> key) {
    this.key = key;
  }

  private void record(
      final long duration, final long exclusiveDuration, final Throwable throwable) {
    assert !closed;
    durationSum += duration;
    exclusiveDurationSum += exclusiveDuration;
    count++;
    errorCount += throwable == null ? 0 : 1;
  }

  /**
   * add other's stats to this one
   *
   * @param other
   */
  public synchronized void merge(final SubTrace other) {
    assert closed;
    assert other.closed;
    durationSum += other.durationSum;
    exclusiveDurationSum += other.exclusiveDurationSum;
    count += other.count;
    errorCount += other.errorCount;
  }

  @Override
  public int compareTo(final SubTrace o) {
    int compare = Long.compare(exclusiveDurationSum, o.exclusiveDurationSum);
    if (compare == 0) {
      compare = Integer.compare(count, o.count);
    }
    return compare;
  }

  // TODO: remove me when we don't set as tag anymore
  public String tagKey() {
    return key.getLeft().getName() + "." + key.getRight();
  }

  public String tagValue() {
    return "exclusiveDuration:"
        + exclusiveDurationSum
        + ", duration:"
        + durationSum
        + ", count:"
        + count
        + ", errorCount:"
        + errorCount;
  }

  // Not thread safe!
  public static class Context {
    private final AgentSpan span;
    private final Map<Pair<Class, String>, SubTrace> aggregates = new HashMap<>();
    private long runningDuration = 0;

    public Context(final AgentSpan span) {
      this.span = span;
    }

    public void collect(
        final Class clazz,
        final String method,
        final long start,
        final long startRunningDuration,
        final Throwable throwable) {
      final long duration = System.nanoTime() - start;
      final long exclusiveDuration = duration - (runningDuration - startRunningDuration);
      runningDuration += exclusiveDuration;

      final Pair<Class, String> key = Pair.of(clazz, method);
      SubTrace subTrace = aggregates.get(key);
      if (subTrace == null) {
        subTrace = new SubTrace(key);
        aggregates.put(key, subTrace);
      }
      subTrace.record(duration, exclusiveDuration, throwable);
    }

    public void close() {
      for (final SubTrace subTrace : aggregates.values()) {
        subTrace.closed = true;
        span.merge(subTrace);
      }
    }

    public long getRunningDuration() {
      return runningDuration;
    }
  }
}
