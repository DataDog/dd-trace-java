package datadog.trace.instrumentation.graphqljava;

import java.util.concurrent.CompletionException;

public final class AsyncExceptionUnwrapper {
  private static final int MAX_UNWRAP_DEPTH = 32;

  private AsyncExceptionUnwrapper() {}

  // Util function to unwrap CompletionException and expose underlying exception
  public static Throwable unwrap(Throwable throwable) {
    if (throwable.getCause() != null && throwable instanceof CompletionException) {
      return throwable.getCause();
    }
    return throwable;
  }
}
