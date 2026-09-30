package datadog.trace.util;

import javax.annotation.Nullable;

/**
 * A per-class latch for an operation that, once it has failed for a class, will fail the same way
 * for every instance of that class: an interface method the class does not implement, for example.
 * For a failure that is the same for everyone, use {@link Latch}.
 *
 * <p>Intended as a {@code static final} anonymous subclass, one per call site and per operation: a
 * class lacking one method says nothing about another, so latches must not be shared. As a constant
 * of a known exact type, the receiver lets the JIT inline {@link #get}, {@link #defaultValue} and
 * {@link #keyOf}. Subclasses decide what counts as a failure in their own {@code try/catch} inside
 * {@link #get}, so checked exceptions and a tight {@code try} scope come for free, and latch
 * through the protected helpers. Only the declaring subclass can change the state.
 *
 * <p>{@link #keyOf} chooses the class the latch is keyed on, and is used by every operation, so the
 * check and the latch cannot disagree. The default is the target's own class. Never key on a
 * wrapper whose contents can differ from one instance to the next; override {@link #keyOf} to
 * return the class of the object that is actually deficient.
 *
 * <p>This is a hint, not a lock. Nothing is stored for a class until it is latched, and until then
 * the cost is one plain flag read. The state is deliberately not atomic. A stale read only costs
 * another failure; a thread always sees its own write, so each thread pays for at most one failure
 * after its own first. Other threads' writes become visible eventually, with no bound on how long
 * that takes. A class is never latched unless the subclass latched it.
 *
 * @param <T> the type of the value the operation is applied to
 * @param <R> the type of the result
 * @param <E> the checked exception {@link #get} may throw
 */
public abstract class ClassLatch<T, R, E extends Exception> {
  /**
   * A call that may fail, typically a method reference such as {@code Connection::getClientInfo}.
   */
  @FunctionalInterface
  public interface Call<T, R, E extends Exception> {
    @Nullable
    R apply(T target) throws E;
  }

  private static final String RECEIVER_PREFIX = "Receiver class ";

  /**
   * Per-class state. Created eagerly so that it is safely published through a final field; it holds
   * nothing for a class until {@link ClassValue#get} is called for it. The value is a JDK type so
   * that nothing from the agent class loader is referenced from an application class.
   */
  private final ClassValue<boolean[]> latched = new Latches();

  /** Whether any class has been latched; keeps the per-class lookup off the common path. */
  private boolean anyLatched;

  private static final class Latches extends ClassValue<boolean[]> {
    @Override
    protected boolean[] computeValue(Class<?> type) {
      return new boolean[1];
    }
  }

  /** Performs the operation. Latch through the protected helpers when it failed for the class. */
  @Nullable
  protected abstract R get(T target) throws E;

  /** The result for a {@code null} or latched target. {@code null} unless overridden. */
  @Nullable
  protected R defaultValue(@Nullable T target) {
    return null;
  }

  /** The class the latch is keyed on. The target's own class unless overridden. */
  protected Class<?> keyOf(T target) {
    return target.getClass();
  }

  /**
   * Performs the operation unless the target is {@code null} or latched, in which case returns
   * {@link #defaultValue}.
   */
  @Nullable
  public final R getOrDefault(@Nullable T target) throws E {
    return target == null || isLatched(target) ? defaultValue(target) : get(target);
  }

  /** Returns whether the operation is being skipped for the target. */
  public final boolean isLatched(@Nullable T target) {
    return target != null && anyLatched && latched.get(keyOf(target))[0];
  }

  /** Skips the operation for the target's key from now on. */
  protected final void latch(T target) {
    latched.get(keyOf(target))[0] = true;
    // after the write: a reader that sees the flag can look the class up, and one that sees the
    // flag but not yet the write just performs the operation once more
    anyLatched = true;
  }

  /** Resumes performing the operation for the target's key, for tests or a policy that retries. */
  protected final void unlatch(T target) {
    if (anyLatched) {
      latched.get(keyOf(target))[0] = false;
    }
  }

  /**
   * Latches the target's key if the error's message names that class as the receiver that lacks the
   * method. Returns whether it latched. An error that does not name the key, for example one thrown
   * inside a wrapper's delegate, is left alone.
   */
  protected final boolean latchIfNamed(T target, AbstractMethodError error) {
    if (isNamedIn(error, keyOf(target))) {
      latch(target);
      return true;
    }
    return false;
  }

  /**
   * For a call to a method that some implementations may lack: returns {@link #defaultValue} if the
   * call raises {@link AbstractMethodError} or {@link UnsupportedOperationException}, latching the
   * key first in the former case if, and only if, the error names it (see {@link #latchIfNamed}).
   * An unsupported operation names no class, so it is never latched, and is caught on every call.
   * Anything else, checked exceptions included, propagates unchanged.
   *
   * <pre>{@code
   * protected Properties get(Connection c) throws SQLException {
   *   return handleAbstractMethod(c, Connection::getClientInfo);
   * }
   * }</pre>
   *
   * Compose {@link #latchIfNamed} and {@link #latch} directly for anything more involved.
   */
  @Nullable
  protected final R handleAbstractMethod(T target, Call<T, R, E> call) throws E {
    try {
      return call.apply(target);
    } catch (AbstractMethodError e) {
      latchIfNamed(target, e);
      return defaultValue(target);
    } catch (UnsupportedOperationException e) {
      // no class to attribute it to, and it may come from a delegate: never latched
      return defaultValue(target);
    }
  }

  /**
   * Matches the receiver class named by HotSpot's message against a class. Two formats exist:
   *
   * <ul>
   *   <li>JDK 11+: {@code Receiver class X does not define or inherit an implementation of the
   *       resolved method ...}
   *   <li>JDK 8: {@code X.method(descriptor)}
   * </ul>
   *
   * A concrete object's class is never the abstract method's declaring class or an interface, so an
   * exact match can only be the receiver. An unparseable message never matches.
   */
  static boolean isNamedIn(AbstractMethodError e, Class<?> type) {
    final String message = e.getMessage();
    if (message == null) {
      return false;
    }
    final String name = type.getName();
    if (message.startsWith(RECEIVER_PREFIX)) {
      final int end = RECEIVER_PREFIX.length() + name.length();
      return message.startsWith(name, RECEIVER_PREFIX.length())
          && message.length() > end
          && message.charAt(end) == ' ';
    }
    if (message.startsWith(name) && message.length() > name.length() + 1) {
      // JDK 8: the method name follows the class name and runs up to the descriptor, so it
      // contains no '.'; that rules out a longer class name sharing this one as a prefix
      final int methodStart = name.length() + 1;
      final int paren = message.indexOf('(', methodStart);
      return message.charAt(name.length()) == '.'
          && paren > methodStart
          && message.indexOf('.', methodStart) < 0;
    }
    return false;
  }
}
