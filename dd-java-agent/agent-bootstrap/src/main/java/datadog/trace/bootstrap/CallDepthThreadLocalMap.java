package datadog.trace.bootstrap;

/**
 * Utility to track nested instrumentation.
 *
 * <p>For example, this can be used to track nested calls to super() in constructors by calling
 * #incrementCallDepth at the beginning of each constructor.
 */
public class CallDepthThreadLocalMap {

  private static final ClassValue<ThreadLocalDepth> TLS =
      new ClassValue<ThreadLocalDepth>() {
        @Override
        protected ThreadLocalDepth computeValue(Class<?> type) {
          return new ThreadLocalDepth();
        }
      };

  public static int incrementCallDepth(final Class<?> k) {
    return TLS.get(k).get().increment();
  }

  public static void reset(final Class<?> k) {
    TLS.get(k).remove();
  }

  private static final class Depth {
    private int depth;

    private Depth() {
      this.depth = 0;
    }

    private int increment() {
      return this.depth++;
    }
  }

  private static final class ThreadLocalDepth extends ThreadLocal<Depth> {
    @Override
    protected Depth initialValue() {
      return new Depth();
    }
  }
}
