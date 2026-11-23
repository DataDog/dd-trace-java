package datadog.metrics.api.statsd;

import datadog.metrics.api.Accumulator;
import java.util.List;
import java.util.function.ToLongFunction;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns an {@link Accumulator} plus the periodic destructive-drain-and-report cycle over it: {@link
 * #inc}/{@link #add} write straight through to the accumulator, {@link #live} peeks it for a
 * diagnostic read, and {@link #flush} drains it and reports the delta to a {@link StatsDClient} --
 * holding onto whatever wasn't delivered and retrying it on the next {@link #flush}, rather than
 * losing it.
 *
 * <p>This isn't a real transaction: statsd delivery is already best-effort over UDP, so there's no
 * rollback on the wire and no way to recover a packet actually lost in transit. It only protects
 * against a local exception (a bad client, a malformed tag, a config error -- not a lost packet)
 * turning "this flush interval under-reports" into "this delta is gone forever."
 *
 * <p>The undelivered remainder is kept as {@link #pending} state on this object, not fed back into
 * the {@link Accumulator} -- {@link Accumulator.RunningTotal#drain} already folds every drained
 * delta into its cumulative total unconditionally, before delivery is attempted, since that total
 * counts real events, independent of whether statsd ever got them. Re-adding the same delta to the
 * accumulator would make a later {@link Accumulator.RunningTotal#drain} see it as new and count it
 * a second time -- {@link #pending} carries it forward for statsd's benefit only. {@link #flush} is
 * assumed single-threaded (it's the body of one periodic scheduled task), so no locking guards it.
 */
@ThreadSafe
public final class StatsDCountReporter<E extends Enum<E> & StatsDCounterKey> {
  private static final Logger log = LoggerFactory.getLogger(StatsDCountReporter.class);

  private final StatsDClient statsDClient;
  private final Accumulator<E> accumulator;
  private final Accumulator.RunningTotal<E> runningTotal;

  /** Undelivered from the last {@link #flush}, to retry on the next one; {@code null} if none. */
  private Accumulator.Counts<E> pending;

  /**
   * @param enumType the enum naming each counter, e.g. {@code MyCounters.class}
   */
  public static <E extends Enum<E> & StatsDCounterKey> StatsDCountReporter<E> of(
      StatsDClient statsDClient, Class<E> enumType) {
    return new StatsDCountReporter<>(statsDClient, Accumulator.of(enumType));
  }

  private StatsDCountReporter(StatsDClient statsDClient, Accumulator<E> accumulator) {
    this.statsDClient = statsDClient;
    this.accumulator = accumulator;
    this.runningTotal = Accumulator.RunningTotal.of(accumulator);
  }

  /** Increments the counter named by {@code key} by one. */
  public void inc(E key) {
    accumulator.inc(key);
  }

  /** Adds {@code delta} to the counter named by {@code key}. */
  public void add(E key, long delta) {
    accumulator.add(key, delta);
  }

  /**
   * The live total -- see {@link Accumulator.RunningTotal#live}. For a diagnostic read (e.g. a
   * {@code summary()}), never for deciding what to report: {@link #flush} owns that.
   */
  public Accumulator.Counts<E> live() {
    return runningTotal.live();
  }

  /**
   * Drains the accumulator and reports the delta (plus anything still owed from a prior failed
   * attempt), holding onto whatever isn't confirmed delivered this time for the next {@link
   * #flush}.
   */
  public void flush() {
    Accumulator.Counts<E> drained = runningTotal.drain();
    Accumulator.Counts<E> toReport = pending == null ? drained : pending.plus(drained);
    pending = report(toReport);
  }

  /**
   * @return {@code null} if every counter was delivered, otherwise a {@link Accumulator.Counts}
   *     holding whatever wasn't attempted or confirmed sent
   */
  private Accumulator.Counts<E> report(Accumulator.Counts<E> counts) {
    List<E> keys = counts.keys();
    for (int i = 0; i < keys.size(); i++) {
      E key = keys.get(i);
      long delta = counts.get(key);
      if (delta != 0) {
        try {
          statsDClient.count(key.getMetricName(), delta, key.getTags());
        } catch (RuntimeException e) {
          log.debug(
              "Failed to report {}, compensating {} undelivered counter(s)",
              key,
              keys.size() - i,
              e);
          return counts.from(i);
        }
      }
    }
    return null;
  }

  /**
   * Reports every nonzero entry of {@code values}/{@code counts} directly -- no accumulator, no
   * compensation.
   */
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
