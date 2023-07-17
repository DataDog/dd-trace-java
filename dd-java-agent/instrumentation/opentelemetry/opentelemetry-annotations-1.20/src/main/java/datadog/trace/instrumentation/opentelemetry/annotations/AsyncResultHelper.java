package datadog.trace.instrumentation.opentelemetry.annotations;

import static datadog.trace.instrumentation.opentelemetry.annotations.WithSpanDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;

public class AsyncResultHelper {
  /**
   * Look for asynchronous result and decorate it with span finisher.
   *
   * @param result The result to check type.
   * @param span The related span to finish.
   * @return An asynchronous result that will finish the span if the result is asynchronous, {@code
   *     null} otherwise.
   */
  public static Object handleAsyncResult(Object result, AgentSpan span) {
    if (result instanceof CompletableFuture<?>) {
      CompletableFuture<?> completableFuture = (CompletableFuture<?>) result;
      if (!((CompletableFuture<?>) result).isDone()) {
        return completableFuture.whenComplete(finishSpan(span));
      }
    } else if (result instanceof CompletionStage<?>) {
      CompletionStage<?> completionStage = (CompletionStage<?>) result;
      completionStage.whenComplete(finishSpan(span));
    }
    return null;
  }

  private static BiConsumer<Object, Throwable> finishSpan(AgentSpan span) {
    return (o, throwable) -> {
      DECORATE.onError(span, throwable);
      span.finish();
    };
  }
}
