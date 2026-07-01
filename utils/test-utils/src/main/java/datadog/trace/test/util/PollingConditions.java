package datadog.trace.test.util;

import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.test.util.ThreadUtils.ThrowingRunnable;

/**
 * A small Java port of Spock's {@code spock.util.concurrent.PollingConditions}. It repeatedly
 * evaluates a block of assertions until they pass or a timeout elapses.
 *
 * <p>The condition block is a {@link ThrowingRunnable}, so it may throw checked exceptions. Any
 * {@link Throwable} it throws is treated as "not satisfied yet" and triggers another attempt.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * // timeout defaults to 1 second
 * new PollingConditions().eventually(() -> assertTrue(done.get()));
 *
 * // timeout-only is the common case
 * new PollingConditions(5).eventually(() -> assertEquals(2, requests.size()));
 *
 * // all knobs, chained
 * new PollingConditions()
 *     .timeout(15).initialDelay(1).delay(0.5).factor(1)
 *     .eventually(() -> assertTrue(done.get()));
 * }</pre>
 */
public class PollingConditions {
  private double timeout = 1; // seconds
  private double initialDelay = 0; // seconds
  private double delay = 0.1; // seconds
  private double factor = 1.0; // delay multiplier between attempts

  public PollingConditions() {}

  /**
   * @param timeoutSeconds the timeout in seconds (the most common single setting).
   * @throws IllegalArgumentException if {@code timeoutSeconds} is negative.
   */
  public PollingConditions(double timeoutSeconds) {
    this.timeout = requireNonNegative(timeoutSeconds, "timeout");
  }

  /**
   * @param seconds how long to keep retrying before failing. Defaults to {@code 1}.
   */
  public PollingConditions timeout(double seconds) {
    this.timeout = requireNonNegative(seconds, "timeout");
    return this;
  }

  /**
   * @param seconds how long to wait before the very first attempt. Defaults to {@code 0}.
   */
  public PollingConditions initialDelay(double seconds) {
    this.initialDelay = requireNonNegative(seconds, "initialDelay");
    return this;
  }

  /**
   * @param seconds how long to wait between attempts. Defaults to {@code 0.1}.
   */
  public PollingConditions delay(double seconds) {
    this.delay = requireNonNegative(seconds, "delay");
    return this;
  }

  /**
   * @param factor multiplier applied to the delay after each attempt (exponential back-off).
   *     Defaults to {@code 1.0} (constant delay).
   */
  public PollingConditions factor(double factor) {
    this.factor = requireNonNegative(factor, "factor");
    return this;
  }

  /** Retries {@code conditions} until it passes or the configured {@link #timeout} elapses. */
  public void eventually(ThrowingRunnable conditions) {
    within(this.timeout, conditions);
  }

  /**
   * Retries {@code conditions} until it passes or {@code seconds} elapse, overriding the configured
   * {@link #timeout} for this call.
   */
  public void within(double seconds, ThrowingRunnable conditions) {
    final long timeoutMillis = toMillis(requireNonNegative(seconds, "timeout"));
    final long start = System.currentTimeMillis();
    if (this.initialDelay > 0) {
      sleep(toMillis(this.initialDelay));
    }
    long currDelay = toMillis(this.delay);
    int attempts = 0;
    long elapsed = 0;
    Throwable lastFailure = null;
    while (elapsed <= timeoutMillis) {
      try {
        attempts++;
        conditions.run();
        return;
      } catch (final Throwable e) {
        elapsed = System.currentTimeMillis() - start;
        lastFailure = e;
        // Never sleep past the deadline.
        final long sleepMillis =
            Math.min(currDelay, start + timeoutMillis - System.currentTimeMillis());
        if (sleepMillis > 0) {
          sleep(sleepMillis);
        }
        currDelay = (long) (currDelay * factor);
      }
    }
    throw new AssertionError(
        String.format(
            "Condition not satisfied after %1.2f seconds and %d attempts",
            elapsed / 1000d, attempts),
        lastFailure);
  }

  private static long toMillis(final double seconds) {
    return Math.round(seconds * 1000);
  }

  private static double requireNonNegative(double value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must be >= 0 but was " + value);
    }
    return value;
  }

  // VisibleForTesting to allow test code to override it and avoid flaky timing-based assertions.
  @VisibleForTesting
  void sleep(final long millis) {
    try {
      Thread.sleep(millis);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting for condition", e);
    }
  }
}
