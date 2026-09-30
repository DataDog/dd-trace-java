package datadog.trace.util;

import javax.annotation.Nullable;

/**
 * Calls a method that some implementations of an interface may lack, and stops paying for the
 * resulting {@link AbstractMethodError} once it is known that a class lacks it.
 *
 * <p>Intended as a {@code static final} field, one per call site: the instance only holds state.
 * Until a class has been latched, the cost on the happy path is one plain flag read; the per-class
 * lookup only happens once something has been latched. The per-class state is deliberately not
 * atomic: a late-visible write costs one more caught error, never a wrong result.
 *
 * <p>A class is latched only when the error is attributed to exactly that class. HotSpot's message
 * names the receiver class that lacks the implementation, so a wrapper delegating to a deficient
 * object is never latched -- its contents may differ from one instance to the next. If the error
 * cannot be attributed (wrapper, unparseable message, another VM) nothing is latched and every call
 * behaves as it would without the guard.
 *
 * <p>{@link AbstractMethodError} and {@link UnsupportedOperationException} are both treated as
 * "this implementation does not support the method" and yield {@code null}. Only the former can be
 * attributed to a class, so only the former is latched; an unsupported operation is caught on every
 * call. Anything else, checked exceptions included, propagates to the caller unchanged.
 */
public final class AbstractMethodGuard {

  /** A method reference such as {@code Connection::getClientInfo}. */
  @FunctionalInterface
  public interface Call<T, R, E extends Exception> {
    R apply(T target) throws E;
  }

  private static final String RECEIVER_PREFIX = "Receiver class ";

  /**
   * Per-class latch. Created eagerly so that it is safely published through a final field: it holds
   * nothing for a class until {@link ClassValue#get} is called for it. The value type is a JDK type
   * so that nothing from the agent class loader is referenced from an application class.
   */
  private final ClassValue<boolean[]> latched = new Latches();

  /**
   * Whether any class has been latched; keeps the per-class lookup off the common path. Plain on
   * purpose: a stale read only costs one more caught error, and {@code volatile} measured
   * noticeably slower on the working path (see {@code AbstractMethodGuardBenchmark}).
   */
  private boolean anyLatched;

  private static final class Latches extends ClassValue<boolean[]> {
    @Override
    protected boolean[] computeValue(Class<?> type) {
      return new boolean[1];
    }
  }

  /**
   * Invokes {@code call} on {@code target}, returning {@code null} if the target's class is known
   * to lack the method, if it just turned out to, or if it reported the operation as unsupported. A
   * {@code null} target also returns {@code null}, without latching.
   */
  @Nullable
  public <T, R, E extends Exception> R invokeOrNull(@Nullable T target, Call<T, R, E> call)
      throws E {
    if (target == null) {
      return null;
    }
    final Class<?> type = target.getClass();
    if (anyLatched && latched.get(type)[0]) {
      return null;
    }
    try {
      return call.apply(target);
    } catch (AbstractMethodError e) {
      if (isAttributedTo(e, type)) {
        latch(type);
      }
      return null;
    } catch (UnsupportedOperationException e) {
      // no class to attribute it to, and it may come from a delegate: never latched
      return null;
    }
  }

  /** Returns whether {@code type} is known to lack the method. */
  public boolean isLatched(Class<?> type) {
    return anyLatched && latched.get(type)[0];
  }

  private void latch(Class<?> type) {
    latched.get(type)[0] = true;
    // after the write, so a reader that sees the flag can look the class up; a reader that sees
    // the flag but not yet the write just calls once more
    anyLatched = true;
  }

  /**
   * Matches the receiver class named by HotSpot's message against the class of the object that was
   * called. Two formats exist:
   *
   * <ul>
   *   <li>JDK 11+: {@code Receiver class X does not define or inherit an implementation of the
   *       resolved method ...}
   *   <li>JDK 8: {@code X.method(descriptor)}
   * </ul>
   *
   * A concrete object's class is never the abstract method's declaring class or an interface, so an
   * exact match can only be the receiver.
   */
  static boolean isAttributedTo(AbstractMethodError e, Class<?> type) {
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
