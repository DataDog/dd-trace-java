package datadog.trace.util;

import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * A one-way, call-site-wide latch for an operation that fails the same way for everyone once it has
 * failed, such as reading a field that is missing from the classes on the classpath. A failure that
 * depends on the receiver's class needs per-class state, which this does not keep.
 *
 * <p>Intended as a {@code static final} anonymous subclass, one per call site: the receiver is then
 * a constant of a known exact type, so the JIT can inline {@link #apply}. Subclasses decide what
 * counts as a failure in their own {@code try/catch} inside {@link #apply}, so checked exceptions
 * and a tight {@code try} scope come for free, and call {@link #latch()} themselves.
 *
 * <p>{@link #fallback} is what every call yields once latched: {@code null} unless overridden.
 * Override it when the call site has a known answer for the failed case, rather than passing the
 * same default to {@link #tryApplyOrDefault} at every call.
 *
 * <p>This is a hint, not a lock. The flag is deliberately plain. A stale read only costs another
 * failure; a thread always sees its own write, so each thread pays for at most one failure after
 * its own first. Other threads' writes become visible eventually, with no bound on how long that
 * takes.
 *
 * @param <T> the type of the value the operation is applied to
 * @param <R> the type of the result
 * @param <E> the checked exception {@link #apply} may throw
 */
public abstract class Latch<T, R, E extends Exception> {
  private boolean latched;

  /** Performs the operation. Call {@link #latch()} when it has failed in a way that will recur. */
  @Nullable
  protected abstract R apply(T target) throws E;

  /**
   * What every call yields once latched, instead of the operation. {@code null} unless overridden.
   */
  @Nullable
  protected R fallback(T target) throws E {
    return null;
  }

  /**
   * Performs the operation unless latched, in which case returns {@link #fallback}. A {@code null}
   * result means nothing is available: neither the operation nor the fallback produced a value.
   */
  @Nullable
  public final R tryApply(T target) throws E {
    return latched ? fallback(target) : apply(target);
  }

  /**
   * Like {@link #tryApply}, but returns {@code defaultValue} when there is nothing available. It is
   * also used when the operation or {@link #fallback} itself produced {@code null}, so a call and a
   * skipped call always agree.
   */
  public final R tryApplyOrDefault(T target, R defaultValue) throws E {
    final R result = tryApply(target);
    return result != null ? result : defaultValue;
  }

  /**
   * For a read of a field that some classes on the classpath may lack: latches if the call raises
   * {@link NoSuchFieldError}, then rethrows it so the first failure is still reported; only later,
   * skipped calls yield {@link #fallback}. A missing field is the same for every receiver, so one
   * latch covers the site. Anything else propagates without latching.
   *
   * <pre>{@code
   * protected Boolean apply(ByteQuadsCanonicalizer symbols) {
   *   return handleNoSuchField(symbols, s -> s._interner != null);
   * }
   * }</pre>
   *
   * A field read throws nothing checked, so the read is a plain {@link Function}. Unlike a helper
   * that swallows the failure, this rethrows it, so the first failure is still reported.
   */
  @Nullable
  protected final R handleNoSuchField(T target, Function<T, R> read) {
    try {
      return read.apply(target);
    } catch (NoSuchFieldError e) {
      latch();
      throw e;
    }
  }

  /** Returns whether the operation is being skipped. */
  public final boolean isLatched() {
    return latched;
  }

  /** Skips the operation from now on. */
  protected final void latch() {
    latched = true;
  }

  /** Resumes performing the operation, for tests or for a policy that retries. */
  protected final void unlatch() {
    latched = false;
  }
}
