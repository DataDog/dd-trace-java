package datadog.trace.instrumentation.httpclient;

import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import java.util.concurrent.CompletableFuture;

public final class CompletableFutureWrapper {

  private CompletableFutureWrapper() {}

  public static <T> CompletableFuture<T> wrap(
      CompletableFuture<T> future, ContextContinuation continuation) {
    CompletableFuture<T> result = new CompletableFuture<>();
    future.whenComplete(
        (T value, Throwable throwable) -> {
          try (ContextScope scope = continuation.resume()) {
            if (throwable != null) {
              result.completeExceptionally(throwable);
            } else {
              result.complete(value);
            }
          }
        });

    return result;
  }
}
