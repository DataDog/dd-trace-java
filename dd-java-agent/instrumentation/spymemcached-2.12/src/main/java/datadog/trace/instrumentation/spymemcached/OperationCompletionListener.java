package datadog.trace.instrumentation.spymemcached;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.ExecutionException;
import net.spy.memcached.internal.OperationFuture;

public class OperationCompletionListener extends CompletionListener<OperationFuture<?>>
    implements net.spy.memcached.internal.OperationCompletionListener {
  public OperationCompletionListener(final AgentSpan span, final String methodName) {
    super(span, methodName);
  }

  @Override
  public void onComplete(final OperationFuture<?> future) {
    closeAsyncSpan(future);
  }

  @Override
  protected void processResult(final AgentSpan span, final OperationFuture<?> future)
      throws ExecutionException, InterruptedException {
    future.get();
  }
}
