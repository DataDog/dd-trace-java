package datadog.trace.bootstrap.instrumentation.api;

import datadog.context.Context;

/**
 * A helper for accessing methods that rely on new Java 8 bytecode features such as calling a static
 * interface methods. In instrumentation, we may need to call these methods in code that is inlined
 * into an instrumented class, however many times the instrumented class has been compiled to a
 * previous version of bytecode, and so we cannot inline calls to static interface methods, as those
 * were not supported prior to Java 8 and will lead to a class verification error.
 */
public class Java8BytecodeBridge {
  /**
   * @see Context#root()
   */
  public static Context getRootContext() {
    return Context.root();
  }

  /**
   * @see Context#current()
   */
  public static Context getCurrentContext() {
    return Context.current();
  }

  /**
   * @see Context#from(Object)
   */
  public static Context getContextFrom(Object carrier) {
    return Context.from(carrier);
  }

  /**
   * @see Context#detachFrom(Object)
   */
  public static Context detachContextFrom(Object carrier) {
    return Context.detachFrom(carrier);
  }

  /**
   * @see AgentSpan#fromContext(Context)
   */
  public static AgentSpan spanFromContext(Context context) {
    return AgentSpan.fromContext(context);
  }
}
