package datadog.trace.util;

import javax.annotation.Nullable;

/**
 * A self-resetting latch for an operation whose failure depends on the input, such as parsing a
 * value from a producer that sometimes sends garbage. Unlike {@code Latch} and {@link ClassLatch},
 * it never skips the operation outright: a bad input says nothing about the next one. Instead, a
 * failure engages a cheap pre-check, {@link #isKnownToFail}, so that further bad input is turned
 * away before the operation pays for throwing, and {@link #closeAfter} consecutive successes
 * disengage it again. Disengaged, it adds one plain field read to the operation's own work; {@code
 * AdaptiveLatchBenchmark} measures what that costs.
 *
 * <p>It only helps while bad input arrives more often than once per {@link #closeAfter} calls.
 * Spaced further apart, the latch disengages between them and every bad input still throws.
 *
 * <p>Intended as a {@code static final} field of a named final subclass, one per call site: the
 * receiver is then a constant of a known exact type, so the JIT can inline the hooks. The subclass
 * is the strategy: {@link #apply} does the operation optimistically and may throw {@code X} for bad
 * input, and {@link #isKnownToFail} must be correct, returning {@code true} only for input that
 * {@link #apply} would definitely reject. A pre-check that is too strict turns away good input; one
 * that is too lenient only costs a real failure, which re-arms the latch.
 *
 * <p>{@link #fallback} is what a failed or turned-away input yields: {@code null} unless
 * overridden, as on {@code Latch} and {@link ClassLatch}.
 *
 * <p>This is a hint, not a lock. The state is a plain counter, and it counts calls, not time. A
 * stale or lost update only costs one more pre-check or one more real failure, never a wrong
 * result.
 *
 * @param <T> the type of the value the operation is applied to
 * @param <R> the type of the result
 * @param <X> the failure that engages the latch; anything else propagates unchanged
 */
public abstract class AdaptiveLatch<T, R, X extends RuntimeException> {
  /** Consecutive successes that disengage the latch, unless {@link #closeAfter} is overridden. */
  public static final int DEFAULT_CLOSE_AFTER = 20;

  private final Class<X> failureType;

  /** 0 when disengaged; otherwise the successes left before disengaging. */
  private int remaining;

  protected AdaptiveLatch(Class<X> failureType) {
    this.failureType = failureType;
  }

  /** Performs the operation optimistically. May throw {@code X} for bad input. */
  @Nullable
  protected abstract R apply(T input);

  /**
   * A cheap pre-check, consulted only while engaged: {@code true} only if {@link #apply} would
   * definitely fail for {@code input}. Must not throw.
   */
  protected abstract boolean isKnownToFail(T input);

  /** What a failed or turned-away input yields. {@code null} unless overridden. */
  @Nullable
  protected R fallback(T input) {
    return null;
  }

  /** How many consecutive successes disengage the latch. */
  protected int closeAfter() {
    return DEFAULT_CLOSE_AFTER;
  }

  /**
   * Performs the operation, returning {@link #fallback} if it fails with {@code X}. While engaged,
   * input that {@link #isKnownToFail} is turned away without performing the operation, so no
   * exception is built at all.
   */
  @Nullable
  public final R tryApply(T input) {
    final int remaining = this.remaining;
    if (remaining > 0 && isKnownToFail(input)) {
      return fallback(input);
    }
    try {
      final R result = apply(input);
      if (remaining > 0) {
        this.remaining = remaining - 1;
      }
      return result;
    } catch (RuntimeException e) {
      if (!failureType.isInstance(e)) {
        throw e;
      }
      this.remaining = closeAfter();
      return fallback(input);
    }
  }

  /** Returns whether the pre-check is currently consulted. */
  public final boolean isEngaged() {
    return remaining > 0;
  }
}
