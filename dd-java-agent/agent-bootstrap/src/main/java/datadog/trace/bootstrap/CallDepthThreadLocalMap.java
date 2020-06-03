package datadog.trace.bootstrap;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility to track nested instrumentation.
 *
 * <p>For example, this can be used to track nested calls to super() in constructors by calling
 * #incrementCallDepth at the beginning of each constructor.
 */
public class CallDepthThreadLocalMap {
  private static final ThreadLocal<Map<Object, Depth>> TLS =
      new ThreadLocal<Map<Object, Depth>>() {
        @Override
        public Map<Object, Depth> initialValue() {
          return new HashMap<>();
        }
      };

  public static int incrementCallDepth(final Object k) {
    final Map<Object, Depth> map = TLS.get();
    Depth depth = map.get(k);
    if (depth == null) {
      map.put(k, new Depth());
      return 0;
    } else {
      return depth.increment();
    }
  }

  public static void reset(final Object k) {
    TLS.get().remove(k);
  }

  private static final class Depth {
    private int depth;

    private Depth() {
      this.depth = 0;
    }

    private int increment() {
      return this.depth = this.depth + 1;
    }
  }
}
