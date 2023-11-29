package datadog.trace.bootstrap.instrumentation.decorator;

import static java.util.Collections.singletonList;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;

/**
 * This decorator handles asynchronous result types, finishing spans only when the async calls are
 * complete. The different async types are supported using {@link AsyncResultSupportExtension} that
 * should be registered using {@link #registerExtension(AsyncResultSupportExtension)} first.
 */
public abstract class AsyncResultDecorator extends BaseDecorator {
  private static final CopyOnWriteArrayList<AsyncResultSupportExtension> EXTENSIONS =
      new CopyOnWriteArrayList<>(
          singletonList(new JavaUtilConcurrentAsyncResultSupportExtension()));

  private static final ClassValue<AsyncResultSupportExtension> EXTENSION_CLASS_VALUE =
      new ClassValue<AsyncResultSupportExtension>() {
        @Override
        protected AsyncResultSupportExtension computeValue(Class<?> type) {
          return EXTENSIONS.stream()
              .filter(extension -> extension.supports(type))
              .findFirst()
              .orElse(null);
        }
      };

  /**
   * Registers an extension to add supported async types.
   *
   * @param extension The extension to register.
   */
  public static void registerExtension(AsyncResultSupportExtension extension) {
    if (extension != null) {
      EXTENSIONS.add(extension);
    }
  }

  /**
   * Look for asynchronous result and decorate it with span finisher. If the result is not
   * asynchronous, it will be return unmodified and span will be finished.
   *
   * @param result The result to check type.
   * @param span The related span to finish.
   * @return An asynchronous result that will finish the span if the result is asynchronous, the
   *     original result otherwise.
   */
  public Object wrapAsyncResultOrFinishSpan(final Object result, final AgentSpan span) {
    AsyncResultSupportExtension extension;
    if (result != null && (extension = EXTENSION_CLASS_VALUE.get(result.getClass())) != null) {
      Object applied = extension.apply(result, span);
      if (applied != null) {
        return applied;
      }
    }
    // If no extension was applied, immediately finish the span and return the original result
    span.finish();
    return result;
  }

  /**
   * This interface defines asynchronous result type support extension. It allows deferring the
   * support implementations where types are available on classpath.
   */
  public interface AsyncResultSupportExtension {
    /**
     * Checks whether this extensions support a result type.
     *
     * @param result The result type to check.
     * @return {@code true} if the type is supported by this extension, {@code false} otherwise.
     */
    boolean supports(Class<?> result);

    /**
     * Applies the extension to the async result.
     *
     * @param result The async result.
     * @param span The related span.
     * @return The result object to return (can be the original result if not modified), or {@code
     *     null} if the extension could not be applied.
     */
    Object apply(Object result, AgentSpan span);
  }

  private static class JavaUtilConcurrentAsyncResultSupportExtension
      implements AsyncResultSupportExtension {
    @Override
    public boolean supports(Class<?> result) {
      return CompletableFuture.class.isAssignableFrom(result)
          || CompletionStage.class.isAssignableFrom(result);
    }

    @Override
    public Object apply(Object result, AgentSpan span) {
      if (result instanceof CompletableFuture<?>) {
        CompletableFuture<?> completableFuture = (CompletableFuture<?>) result;
        if (!completableFuture.isDone() && !completableFuture.isCancelled()) {
          return completableFuture.whenComplete(finishSpan(span));
        }
      } else if (result instanceof CompletionStage<?>) {
        CompletionStage<?> completionStage = (CompletionStage<?>) result;
        return completionStage.whenComplete(finishSpan(span));
      }
      return null;
    }

    private <T> BiConsumer<T, Throwable> finishSpan(AgentSpan span) {
      return (o, throwable) -> {
        if (throwable != null) {
          span.addThrowable(
              throwable instanceof ExecutionException || throwable instanceof CompletionException
                  ? throwable.getCause()
                  : throwable);
        }
        span.finish();
      };
    }
  }
}
