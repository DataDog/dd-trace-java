package datadog.trace.instrumentation.graphqljava;

import java.util.concurrent.CompletionException;

public final class AsyncExceptionUnwrapper {
  private static final int MAX_UNWRAP_DEPTH = 32;

  private AsyncExceptionUnwrapper() {}

  // Util function to unwrap CompletionException and expose underlying exception
  public static Throwable unwrap(Throwable throwable) {
    Throwable t = throwable;
    int depth = 0;
    while (t != null
        && t.getCause() != null
        && depth++ < MAX_UNWRAP_DEPTH
        && (t instanceof CompletionException)) {
      t = t.getCause();
    }
    return t;
  }
}
