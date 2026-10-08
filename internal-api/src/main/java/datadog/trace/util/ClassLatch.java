package datadog.trace.util;

import datadog.trace.api.function.Strategy;
import datadog.trace.api.function.StrategyConsumer;
import datadog.trace.api.function.ThrowingFunction;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A per-class latch for an operation that, once it has failed for a class, will fail the same way
 * for every instance of that class: an interface method the class does not implement, for example.
 * A failure that is the same for everyone, whatever the class, needs only a single flag and no
 * per-class state.
 *
 * <p>Intended as a {@code static final} anonymous subclass, one per call site and per operation: a
 * class lacking one method says nothing about another, so latches must not be shared. As a constant
 * of a known exact type, the receiver lets the JIT inline {@link #apply} and {@link #keyOf}.
 * Subclasses decide what counts as a failure in their own {@code try/catch} inside {@link #apply},
 * so checked exceptions and a tight {@code try} scope come for free, and latch through the
 * protected helpers. Only the declaring subclass can change the state.
 *
 * <p>{@link #fallback} is what a latched target yields instead of the operation: {@code null}
 * unless overridden. The helpers return it too when the operation fails, so the failing call and
 * every skipped call after it agree. Override it when the operation has a slower alternative, such
 * as an older API, rather than checking {@link #isLatched} at the call site.
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
 * @param <E> the checked exception {@link #apply} may throw
 */
@ThreadSafe
public abstract class ClassLatch<T, R, E extends Exception> {
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
  protected abstract R apply(T target) throws E;

  /**
   * What a latched target yields instead of the operation; the helpers also return it when the
   * operation fails. {@code null} unless overridden. A latch that calls {@link #latch} directly
   * should return this too, so that the failing call and later skipped calls agree.
   */
  @Nullable
  protected R fallback(T target) throws E {
    return null;
  }

  /** The class the latch is keyed on. The target's own class unless overridden. */
  protected Class<?> keyOf(T target) {
    return target.getClass();
  }

  /**
   * Performs the operation, returning {@code null} for a {@code null} target and {@link #fallback}
   * for a latched one. A {@code null} result means nothing is available: the target was {@code
   * null}, or neither the operation nor the fallback produced a value.
   */
  @Nullable
  public final R tryApply(@Nullable T target) throws E {
    if (target == null) {
      return null;
    }
    if (isLatched(target)) {
      return fallback(target);
    }
    try {
      return apply(target);
    } catch (NoSuchMethodError e) {
      // a method reference passed to handleNoSuchMethod resolves where it is written, in apply's
      // own frame, so a missing target method surfaces here, outside the helper's own try, as the
      // invokedynamic call site's own linkage failure; HotSpot reports this directly as
      // NoSuchMethodError on some JVM versions
      latch(target);
      return fallback(target);
    } catch (BootstrapMethodError e) {
      // on other JVM versions the same linkage failure is wrapped instead
      if (e.getCause() instanceof NoSuchMethodError) {
        latch(target);
        return fallback(target);
      }
      throw e;
    }
  }

  /**
   * Like {@link #tryApply}, but returns {@code defaultValue} when there is nothing available. It is
   * also used when the operation or {@link #fallback} itself produced {@code null}, so a call and a
   * skipped call always agree.
   */
  public final R tryApplyOrDefault(@Nullable T target, R defaultValue) throws E {
    final R result = tryApply(target);
    return result != null ? result : defaultValue;
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
   * Latches the target's key if the error's message names that class as the receiver that lacks
   * {@code methodName}, or, when the JVM gave no message, if that class still has {@code
   * methodName} as an abstract method. Returns whether it latched. An error that does not name the
   * key, for example one thrown inside a wrapper's delegate, is left alone — and so is one that
   * names the key but for a different method, for example one the guarded method's own
   * implementation calls internally.
   */
  protected final boolean latchIfNamed(T target, String methodName, AbstractMethodError error) {
    if (isNamedIn(error, keyOf(target), methodName)) {
      latch(target);
      return true;
    }
    return false;
  }

  /**
   * For a call to a method that some implementations may lack: returns {@link #fallback} if the
   * call raises {@link AbstractMethodError} or {@link UnsupportedOperationException}, latching the
   * key first in the former case if, and only if, the error is attributed to it (see {@link
   * #latchIfNamed}). An error that is not attributed to the key still yields the fallback; it just
   * is not latched. An unsupported operation names no class, so it is never latched, and is caught
   * on every call. Anything else, checked exceptions included, propagates unchanged.
   *
   * <pre>{@code
   * protected Properties apply(Connection c) throws SQLException {
   *   return handleAbstractMethod(c, "getClientInfo", Connection::getClientInfo);
   * }
   * }</pre>
   *
   * This covers only {@link AbstractMethodError}. A method that may also be missing from the
   * classes on the classpath altogether raises {@link NoSuchMethodError}, which this does not
   * handle; see {@link #handleNoSuchMethod} and {@link #handleNoSuchOrAbstractMethod}, and prefer
   * the latter when unsure which a call site can see.
   *
   * <p>{@code methodName} must be {@code call}'s own method, not merely some method of {@code T}:
   * the error's message can name the key class while blaming a different method that the guarded
   * method's implementation happens to call internally, and only a method name check tells the two
   * apart.
   *
   * <p>Pass a method reference or a non-capturing lambda, and keep this method small so it inlines:
   * that is what lets the JIT see the exact function at each call site (see {@link Strategy}).
   * Compose {@link #latchIfNamed} and {@link #latch} directly for anything more involved.
   */
  @Nullable
  @StrategyConsumer
  protected final R handleAbstractMethod(
      T target, String methodName, @Strategy ThrowingFunction<T, R, E> call) throws E {
    try {
      return call.apply(target);
    } catch (AbstractMethodError e) {
      latchIfNamed(target, methodName, e);
      return fallback(target);
    } catch (UnsupportedOperationException e) {
      // no class to attribute it to, and it may come from a delegate: never latched
      return fallback(target);
    }
  }

  /**
   * For a call to a method that is missing from the classes on the classpath altogether, for
   * example because the caller was built against a newer library than the one present: returns
   * {@link #fallback} if the call raises {@link NoSuchMethodError}, latching the target's key
   * first. Anything else propagates unchanged; see {@link #handleNoSuchOrAbstractMethod} if the
   * method may instead be present but unimplemented by some classes ({@link AbstractMethodError}).
   *
   * <p>A {@link NoSuchMethodError} is a failure of resolution, which the JVM keeps for the call
   * site, but it can equally be raised by a call made <em>inside</em> one receiver's
   * implementation. Its message names the declared type, not the receiver, so it cannot be
   * attributed to a class. It therefore latches the target's key, never the whole call site: a
   * site-wide failure then costs one throw per receiver class, and an inner failure stays confined
   * to the class that has it.
   *
   * <pre>{@code
   * protected Properties apply(Connection c) throws SQLException {
   *   return handleNoSuchMethod(c, Connection::getClientInfo);
   * }
   * }</pre>
   */
  @Nullable
  @StrategyConsumer
  protected final R handleNoSuchMethod(T target, @Strategy ThrowingFunction<T, R, E> call)
      throws E {
    try {
      return call.apply(target);
    } catch (NoSuchMethodError e) {
      latch(target);
      return fallback(target);
    }
  }

  /**
   * For a call to a method that may be missing ({@link NoSuchMethodError}) or unimplemented by some
   * classes ({@link AbstractMethodError}): both yield {@link #fallback}, as does {@link
   * UnsupportedOperationException}. This is {@link #handleAbstractMethod} and {@link
   * #handleNoSuchMethod} together, with the same latching rules: an {@link AbstractMethodError}
   * latches only if it is attributed to the key (see {@link #latchIfNamed}), and a {@link
   * NoSuchMethodError} latches the target's key. The two are easy to confuse, so prefer this one
   * unless you know which a call site can see.
   *
   * <pre>{@code
   * protected Properties apply(Connection c) throws SQLException {
   *   return handleNoSuchOrAbstractMethod(c, "getClientInfo", Connection::getClientInfo);
   * }
   * }</pre>
   */
  @Nullable
  @StrategyConsumer
  protected final R handleNoSuchOrAbstractMethod(
      T target, String methodName, @Strategy ThrowingFunction<T, R, E> call) throws E {
    try {
      return handleAbstractMethod(target, methodName, call);
    } catch (NoSuchMethodError e) {
      latch(target);
      return fallback(target);
    }
  }

  /**
   * Whether a public method named {@code methodName} is still abstract on {@code type}, as an
   * interface method that a concrete class never implemented is. This attributes the error to the
   * class just as a message naming it would: a wrapper that delegates the method has a concrete
   * implementation, so an error raised by its delegate is not attributed to it. Overloads are not
   * told apart. Each class is scanned once, whatever the answer, so a class that keeps failing
   * without being latched does not repeat the reflection.
   */
  static boolean lacksImplementation(Class<?> type, String methodName) {
    for (final String abstractMethod : ABSTRACT_METHOD_NAMES.get(type)) {
      if (abstractMethod.equals(methodName)) {
        return true;
      }
    }
    return false;
  }

  private static final String[] NO_NAMES = new String[0];

  /**
   * Per class, the names of its public methods that are still abstract. Only computed for classes
   * that failed without a message. The value is a JDK type, like {@link #latched}'s.
   */
  private static final ClassValue<String[]> ABSTRACT_METHOD_NAMES =
      new ClassValue<String[]>() {
        @Override
        protected String[] computeValue(Class<?> type) {
          try {
            int count = 0;
            final Method[] methods = type.getMethods();
            final String[] names = new String[methods.length];
            for (final Method method : methods) {
              if (Modifier.isAbstract(method.getModifiers())) {
                names[count++] = method.getName();
              }
            }
            return count == 0 ? NO_NAMES : Arrays.copyOf(names, count);
          } catch (final SecurityException ignored) {
            return NO_NAMES; // cannot tell, so never latch
          }
        }
      };

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
   * exact match can only be the receiver. The message also names the resolved method itself: that
   * method's implementation can call a different, unimplemented method on the very same receiver,
   * raising an error that names the key class but blames a method other than {@code methodName}, so
   * the method name is checked too. An unparseable message never matches.
   *
   * <p>JDK 8 omits the message once the call site has dispatched to a class that does implement the
   * method, which in production is nearly always. Without a message the class itself is checked
   * instead: see {@link #lacksImplementation}.
   */
  static boolean isNamedIn(AbstractMethodError e, Class<?> type, String methodName) {
    final String message = e.getMessage();
    if (message == null) {
      return lacksImplementation(type, methodName);
    }
    final String name = type.getName();
    if (message.startsWith(RECEIVER_PREFIX)) {
      final int end = RECEIVER_PREFIX.length() + name.length();
      return message.startsWith(name, RECEIVER_PREFIX.length())
          && message.length() > end
          && message.charAt(end) == ' '
          && message.indexOf(methodName + "(", end) > end;
    }
    if (message.startsWith(name) && message.length() > name.length() + 1) {
      // JDK 8: the method name follows the class name and runs up to the descriptor, so it
      // contains no '.'; that rules out a longer class name sharing this one as a prefix
      final int methodStart = name.length() + 1;
      final int paren = message.indexOf('(', methodStart);
      return message.charAt(name.length()) == '.'
          && paren > methodStart
          && message.indexOf('.', methodStart) < 0
          && paren == methodStart + methodName.length()
          && message.regionMatches(methodStart, methodName, 0, methodName.length());
    }
    return false;
  }
}
