package datadog.trace.bootstrap.instrumentation.api;

import datadog.context.Context;
import datadog.context.ContextHandle;
import datadog.context.ContextScope;

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
  public static Context rootContext() {
    return Context.root();
  }

  /**
   * @see Context#current()
   */
  public static Context currentContext() {
    return Context.current();
  }

  /**
   * @see Context#isCaptureEnabled()
   */
  public static boolean isContextCaptureEnabled() {
    return Context.isCaptureEnabled();
  }

  /**
   * @see Context#setCaptureEnabled(boolean)
   */
  public static void setContextCaptureEnabled(boolean enabled) {
    Context.setCaptureEnabled(enabled);
  }

  /**
   * @see AgentSpan#current()
   */
  public static AgentSpan currentSpan() {
    return AgentSpan.current();
  }

  /**
   * @see AgentSpan#fromContext(ContextHandle)
   */
  public static AgentSpan spanFromContext(ContextHandle context) {
    return AgentSpan.fromContext(context);
  }

  /**
   * @see AgentSpan#fromScope(ContextScope)
   */
  public static AgentSpan spanFromScope(ContextScope scope) {
    return AgentSpan.fromScope(scope);
  }

  /**
   * @see Baggage#fromContext(Context)
   */
  public static Baggage baggageFromContext(Context context) {
    return Baggage.fromContext(context);
  }

  private Java8BytecodeBridge() {}
}
