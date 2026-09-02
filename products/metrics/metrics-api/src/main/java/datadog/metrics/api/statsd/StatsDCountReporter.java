package datadog.metrics.api.statsd;

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
}
