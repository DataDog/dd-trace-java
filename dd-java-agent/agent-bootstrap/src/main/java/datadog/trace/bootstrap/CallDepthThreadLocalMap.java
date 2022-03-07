package datadog.trace.bootstrap;

import datadog.trace.api.GenericClassValue;

/**
 * Utility to track nested instrumentation.
 *
 * <p>For example, this can be used to track nested calls to super() in constructors by calling
 * #incrementCallDepth at the beginning of each constructor.
 */
public class CallDepthThreadLocalMap {

  private static final ClassValue<ThreadLocalDepth> TLS =
      GenericClassValue.constructing(ThreadLocalDepth.class);

  public static int incrementCallDepth(final Class<?> k) {
    return TLS.get(k).get().increment();
  }

  public static int getCallDepth(final Class<?> k) {
    return TLS.get(k).get().depth;
  }

  public static int decrementCallDepth(final Class<?> k) {
    return TLS.get(k).get().decrement();
  }

  public static void reset(final Class<?> k) {
    TLS.get(k).get().depth = 0;
  }

  private static final class Depth {
    private int depth;

    private Depth() {
      this.depth = 0;
    }

    private int increment() {
      return this.depth++;
    }

    private int decrement() {
      return --this.depth;
    }
  }

  public static final class ThreadLocalDepth extends ThreadLocal<Depth> {
    @Override
    protected Depth initialValue() {
      return new Depth();
    }
  }
}
