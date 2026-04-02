package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static java.util.Collections.singletonList;

import datadog.trace.api.Platform;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;

public final class AsyncResultExtensions {
  private static final List<AsyncResultExtension> EXTENSIONS =
      new CopyOnWriteArrayList<>(singletonList(new CompletableAsyncResultExtension()));

  private static final ClassValue<AsyncResultExtension> EXTENSION_CLASS_VALUE =
      new ClassValue<AsyncResultExtension>() {
        @Override
        protected AsyncResultExtension computeValue(Class<?> type) {
          return AsyncResultExtensions.registered().stream()
              .filter(extension -> extension.supports(type))
              .findFirst()
              .orElse(null);
        }
      };

  /**
   * Wraps a supported async result so the span is finished when the async computation completes.
   *
   * @return the wrapped async result, or {@code null} if the result type is unsupported or no
   *     wrapping is applied
   */
  public static Object wrapAsyncResult(
      final Object result, final Class<?> resultType, final AgentSpan span) {
    AsyncResultExtension extension;
    if (result != null && (extension = EXTENSION_CLASS_VALUE.get(resultType)) != null) {
      return extension.apply(result, span);
    }
    return null;
  }

  /**
   * Registers an extension to add supported async types.
   *
   * @param extension The extension to register.
   */
  public static void register(AsyncResultExtension extension) {
    if (extension != null) {
      if (Platform.isNativeImageBuilder()
          && extension
              .getClass()
              .getClassLoader()
              .getClass()
              .getName()
              .endsWith("ThrowawayClassLoader")) {
        return; // spring-native expects this to be thrown away, not persisted
      }
      EXTENSIONS.add(extension);
    }
  }

  /** Returns the list of currently registered extensions. */
  public static List<AsyncResultExtension> registered() {
    return EXTENSIONS;
  }

  static final class CompletableAsyncResultExtension implements AsyncResultExtension {
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
  }

  public static <T> BiConsumer<T, Throwable> finishSpan(AgentSpan span) {
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
