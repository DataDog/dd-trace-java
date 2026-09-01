package datadog.metrics.api.statsd;

/**
 * Reports a drained {@code long[]} of counter values -- e.g. from {@code
 * Accumulator.accumulateAndReset(data)} -- to a {@link StatsDClient}, one {@link
 * StatsDClient#count} call per enum constant whose value changed.
 *
 * @see StatsDCounterKey
 */
public final class StatsDCountReporter {
  private StatsDCountReporter() {}

  /**
   * @param values the enum constants naming each counter, e.g. {@code MyCounters.values()}
   * @param counts one value per constant, indexed by {@link Enum#ordinal()} -- e.g. the array
   *     returned by {@code Accumulator.accumulateAndReset(data)}
   * @param tags tags applied uniformly to every reported counter
   */
  public static <E extends Enum<E> & StatsDCounterKey> void report(
      StatsDClient statsDClient, E[] values, long[] counts, String... tags) {
    for (E value : values) {
      long delta = counts[value.ordinal()];
      if (delta != 0) {
        statsDClient.count(value.getMetricName(), delta, tags);
      }
    }
  }
}
