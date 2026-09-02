package datadog.metrics.api.statsd;

import datadog.trace.util.Accumulator;
import java.util.function.ToLongFunction;

/** Reports a batch of per-key deltas to a {@link StatsDClient}, skipping unchanged keys. */
public final class StatsDCountReporter {
  private StatsDCountReporter() {}

  public static <E extends Enum<E> & StatsDCounterKey> void report(
      StatsDClient statsDClient, E[] values, ToLongFunction<E> counts) {
    for (E value : values) {
      long delta = counts.applyAsLong(value);
      if (delta != 0) {
        statsDClient.count(value.getMetricName(), delta, value.getTags());
      }
    }
  }

  /**
   * Convenience for the common case of reporting a drained {@link Accumulator.Counts} directly --
   * the keys come along with it, so the caller doesn't need to separately pass {@code E.values()}.
   */
  public static <E extends Enum<E> & StatsDCounterKey> void report(
      StatsDClient statsDClient, Accumulator.Counts<E> counts) {
    report(statsDClient, counts.keys(), counts::get);
  }
}
