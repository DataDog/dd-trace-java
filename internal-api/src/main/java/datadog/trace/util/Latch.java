package datadog.trace.util;

import javax.annotation.Nullable;

/**
 * A one-way, call-site-wide latch for an operation that fails the same way for everyone once it has
 * failed, such as reading a field that is missing from the classes on the classpath. For a failure
 * that depends on the receiver's class, use {@link ClassLatch}.
 *
 * <p>Intended as a {@code static final} anonymous subclass, one per call site: the receiver is then
 * a constant of a known exact type, so the JIT can inline {@link #get} and {@link #defaultValue}.
 * Subclasses decide what counts as a failure in their own {@code try/catch} inside {@link #get}, so
 * checked exceptions and a tight {@code try} scope come for free, and call {@link #latch()}
 * themselves.
 *
 * <p>This is a hint, not a lock. The flag is deliberately plain. A stale read only costs another
 * failure; a thread always sees its own write, so each thread pays for at most one failure after
 * its own first. Other threads' writes become visible eventually, with no bound on how long that
 * takes.
 *
 * @param <T> the type of the value the operation is applied to
 * @param <R> the type of the result
 * @param <E> the checked exception {@link #get} may throw
 */
public abstract class Latch<T, R, E extends Exception> {
  private boolean latched;

  /** Performs the operation. Call {@link #latch()} when it has failed in a way that will recur. */
  @Nullable
  protected abstract R get(T target) throws E;

  /** The result once latched. {@code null} unless overridden. */
  @Nullable
  protected R defaultValue(T target) {
    return null;
  }

  /** Performs the operation unless latched, in which case returns {@link #defaultValue}. */
  @Nullable
  public final R getOrDefault(T target) throws E {
    return latched ? defaultValue(target) : get(target);
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
