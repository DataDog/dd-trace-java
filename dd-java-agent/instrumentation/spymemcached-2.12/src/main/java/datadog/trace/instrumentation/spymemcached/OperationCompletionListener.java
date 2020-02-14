package datadog.trace.instrumentation.spymemcached;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.ExecutionException;
import net.spy.memcached.MemcachedConnection;
import net.spy.memcached.internal.OperationFuture;

public class OperationCompletionListener
    extends CompletionListener<OperationFuture<? extends Object>>
    implements net.spy.memcached.internal.OperationCompletionListener {
  public OperationCompletionListener(
      final MemcachedConnection connection, final String methodName) {
    super(connection, methodName);
  }

  @Override
  public void onComplete(final OperationFuture<? extends Object> future) {
    closeAsyncSpan(future);
  }

  @Override
  protected void processResult(final AgentSpan span, final OperationFuture<? extends Object> future)
      throws ExecutionException, InterruptedException {
    future.get();
  }
}
