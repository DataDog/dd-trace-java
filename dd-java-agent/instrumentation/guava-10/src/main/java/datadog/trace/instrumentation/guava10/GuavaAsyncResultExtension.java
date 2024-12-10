package datadog.trace.instrumentation.guava10;

import com.google.common.util.concurrent.ListenableFuture;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.EagerHelper;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AsyncResultExtension;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AsyncResultExtensions;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

public class GuavaAsyncResultExtension implements AsyncResultExtension, EagerHelper {
  static {
    AsyncResultExtensions.register(new GuavaAsyncResultExtension());
  }

  /**
   * Register the extension as an {@link AsyncResultExtension} using static class initialization.
   * <br>
   * It uses an empty static method call to ensure the class loading and the one-time-only static
   * class initialization. This will ensure this extension will only be registered once under {@link
   * AsyncResultExtensions}.
   */
  public static void init() {}

  @Override
  public boolean supports(Class<?> result) {
    return ListenableFuture.class.isAssignableFrom(result);
  }

  @Override
  public Object apply(Object result, AgentSpan span) {
    if (result instanceof ListenableFuture) {
      ListenableFuture<?> listenableFuture = (ListenableFuture<?>) result;
      if (!listenableFuture.isDone() && !listenableFuture.isCancelled()) {
        listenableFuture.addListener(
            () -> {
              // Get value to check for execution exception
              try {
                listenableFuture.get();
              } catch (ExecutionException e) {
                span.addThrowable(e.getCause());
              } catch (CancellationException | InterruptedException e) {
                // Ignored
              }
              span.finish();
            },
            Runnable::run);
        return result;
      }
    }
    return null;
  }
}
