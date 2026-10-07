package datadog.trace.util;

import java.lang.reflect.UndeclaredThrowableException;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A self-resetting switch between two ways of performing an operation whose failure depends on the
 * input, such as parsing a value from a producer that sometimes sends garbage: an optimistic path,
 * {@link #apply}, that is fastest on good input but throws {@code X} on bad input, and a cautious
 * path, {@link #applySafely}, that never throws {@code X}. Unlike {@code Latch} and {@link
 * ClassLatch}, it never skips the operation: a bad input says nothing about the next one.
 *
 * <p>Disengaged, every call takes the optimistic path. A failure there engages the latch, and from
 * then on calls take the cautious path until {@code closeAfter} consecutive inputs pass it cleanly.
 * The cautious path reports bad input by returning {@link #reject}, or {@link #repaired} when it
 * still produced a result, either of which restarts that count; anything else it returns counts as
 * clean.
 *
 * <p>The cautious path can be built in several ways:
 *
 * <ul>
 *   <li>an exception-free implementation of the same operation;
 *   <li>a cheap, correct pre-check in front of the optimistic path: {@code return isKnownBad(input)
 *       ? reject(input) : apply(input);}
 *   <li>a repair that turns common bad input into good input before the optimistic path, reporting
 *       each input it had to repair through {@link #repaired}.
 * </ul>
 *
 * <p>Choosing {@code closeAfter} is a rent-or-buy decision. Staying engaged costs the cautious
 * path's overhead on every good input; disengaging costs one optimistic failure when the next bad
 * input arrives. Disengaging once the accumulated overhead would match one failure, that is after
 * about {@code failureCost / cautiousOverhead} good inputs, is never worse than twice the best
 * possible schedule, whatever the input. Measure both costs at a realistic stack depth: the cost of
 * a throw grows with the stack it fills in.
 *
 * <p>Intended as a {@code static final} field of a named final subclass, one per call site: the
 * receiver is then a constant of a known exact type, so the JIT can inline the hooks. Disengaged,
 * the latch adds one plain field read to the optimistic path.
 *
 * <p>This is a hint, not a lock. The state is a plain counter, and it counts calls, not time. A
 * stale or lost update only costs one more cautious call or one more optimistic failure, never a
 * wrong result.
 *
 * @param <T> the type of the value the operation is applied to
 * @param <R> the type of the result
 * @param <X> the failure that engages the latch, checked or not; anything else propagates unchanged
 */
@ThreadSafe
public abstract class AdaptiveLatch<T, R, X extends Exception> {
  private final Class<X> failureType;
  private final int closeAfter;

  /** 0 when disengaged; otherwise the clean calls left before disengaging. */
  private int remaining;

  /**
   * @param failureType the failure of {@link #apply} that engages the latch
   * @param closeAfter how many consecutive clean calls disengage it, at least 1; see the class
   *     comment for how to choose it
   */
  protected AdaptiveLatch(Class<X> failureType, int closeAfter) {
    if (closeAfter < 1) {
      throw new IllegalArgumentException("closeAfter must be at least 1: " + closeAfter);
    }
    this.failureType = failureType;
    this.closeAfter = closeAfter;
  }

  /** The optimistic path: fastest on good input, but may throw {@code X} for bad input. */
  @Nullable
  protected abstract R apply(T input) throws X;

  /**
   * The cautious path: must not throw {@code X}. Returns {@link #reject} for bad input; any other
   * return counts as clean, including {@code null}.
   */
  @Nullable
  protected abstract R applySafely(T input);

  /** How many consecutive clean calls disengage the latch. */
  public final int closeAfter() {
    return closeAfter;
  }

  /** What a rejected input yields. {@code null} unless overridden. */
  @Nullable
  protected R fallback(T input) {
    return null;
  }

  /** Reports bad input from {@link #applySafely}: restarts the count, and returns the fallback. */
  @Nullable
  protected final R reject(T input) {
    remaining = closeAfter;
    return fallback(input);
  }

  /**
   * Reports bad input that {@link #applySafely} repaired: restarts the count, like {@link #reject},
   * but returns the repaired result. Without it, a steady stream of repairable input would count as
   * clean, disengage the latch, and pay one optimistic failure every {@code closeAfter} calls.
   */
  @Nullable
  protected final R repaired(@Nullable R result) {
    remaining = closeAfter;
    return result;
  }

  /**
   * Performs the operation: optimistically while disengaged, cautiously while engaged. An
   * optimistic failure engages the latch, and the same input is then retried cautiously, so a
   * cautious path that can repair it still gets the chance.
   */
  @Nullable
  public final R tryApply(T input) {
    final int remaining = this.remaining;
    if (remaining > 0) {
      // counted as clean up front; reject() re-arms the count if it is not
      this.remaining = remaining - 1;
      return applySafely(input);
    }
    try {
      return apply(input);
    } catch (RuntimeException e) {
      if (!failureType.isInstance(e)) {
        throw e;
      }
      return engage(input);
    } catch (Exception e) {
      // apply declares only X, so any checked exception here is X unless it was thrown sneakily
      if (!failureType.isInstance(e)) {
        throw new UndeclaredThrowableException(e);
      }
      return engage(input);
    }
  }

  private R engage(T input) {
    this.remaining = closeAfter;
    return applySafely(input);
  }

  /** Returns whether calls currently take the cautious path. */
  public final boolean isEngaged() {
    return remaining > 0;
  }
}
