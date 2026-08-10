package datadog.trace.instrumentation.springmessaging;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import io.awspring.cloud.sqs.listener.ListenerExecutionFailedException;
import java.util.function.BiConsumer;

public final class SpringCloudAwsErrorHandlerHelper {
  private SpringCloudAwsErrorHandlerHelper() {}

  public static ListenerExecutionFailedException findListenerExecutionFailedException(
      Throwable error) {
    Throwable current = error;
    while (current != null && !(current instanceof ListenerExecutionFailedException)) {
      Throwable cause = current.getCause();
      if (cause == current) {
        return null;
      }
      current = cause;
    }
    return (ListenerExecutionFailedException) current;
  }

  public static final class CleanupOnError implements BiConsumer<Object, Throwable> {
    private final ContextStore<ListenerExecutionFailedException, State> contextStore;

    public CleanupOnError(ContextStore<ListenerExecutionFailedException, State> contextStore) {
      this.contextStore = contextStore;
    }

    @Override
    public void accept(Object ignored, Throwable error) {
      if (error == null) {
        return;
      }
      ListenerExecutionFailedException listenerException =
          findListenerExecutionFailedException(error);
      if (listenerException != null) {
        SpringMessageErrorHandlerHelper.cancelContinuation(contextStore.get(listenerException));
      }
    }
  }
}
