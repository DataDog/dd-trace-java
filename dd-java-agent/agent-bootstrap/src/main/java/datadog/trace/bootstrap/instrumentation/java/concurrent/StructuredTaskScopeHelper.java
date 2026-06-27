package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static java.lang.invoke.MethodHandles.publicLookup;
import static java.lang.invoke.MethodType.methodType;

import java.lang.invoke.MethodHandle;

/** Helper for the java-concurrent-25.0 {@code StructuredTaskScopeForkInstrumentation}. */
public final class StructuredTaskScopeHelper {
  private static final MethodHandle IS_CANCELLED = resolveIsCancelled();

  private StructuredTaskScopeHelper() {}

  private static MethodHandle resolveIsCancelled() {
    try {
      ClassLoader classLoader = StructuredTaskScopeHelper.class.getClassLoader();
      Class<?> scopeClass =
          Class.forName("java.util.concurrent.StructuredTaskScope", false, classLoader);
      return publicLookup().findVirtual(scopeClass, "isCancelled", methodType(boolean.class));
    } catch (Throwable ignored) {
      return null;
    }
  }

  /**
   * Returns whether the given {@code StructuredTaskScope} has been cancelled.
   *
   * @param scope a {@code java.util.concurrent.StructuredTaskScope} instance.
   * @return {@code true} if the scope is cancelled; {@code false} otherwise, including when {@code
   *     isCancelled()} could not be resolved or invoked.
   */
  public static boolean isCancelled(Object scope) {
    if (IS_CANCELLED == null || scope == null) {
      return false;
    }
    try {
      return (boolean) IS_CANCELLED.invoke(scope);
    } catch (Throwable ignored) {
      return false;
    }
  }
}
