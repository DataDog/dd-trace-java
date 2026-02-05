package datadog.trace.instrumentation.graphqljava;

import java.util.concurrent.CompletionException;

public final class AsyncExceptionUnwrapper {

  private AsyncExceptionUnwrapper() {}

  // Util function to unwrap CompletionException and expose underlying exception
  public static Throwable unwrap(Throwable throwable) {
    if (throwable instanceof CompletionException && throwable.getCause() != null) {
      return throwable.getCause();
    }
    return throwable;
  }
}
