package datadog.trace.instrumentation.httpclient;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import java.util.concurrent.CompletableFuture;

public final class CompletableFutureWrapper {

  private CompletableFutureWrapper() {}

  public static <T> CompletableFuture<T> wrap(
      CompletableFuture<T> future, AgentScope.Continuation continuation) {
    CompletableFuture<T> result = new CompletableFuture<>();
    future.whenComplete(
        (T value, Throwable throwable) -> {
          try (AgentScope scope = continuation.activate()) {
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
