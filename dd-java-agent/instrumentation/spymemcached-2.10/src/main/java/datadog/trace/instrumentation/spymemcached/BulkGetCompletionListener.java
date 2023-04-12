package datadog.trace.instrumentation.spymemcached;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.ExecutionException;
import net.spy.memcached.internal.BulkGetFuture;

public class BulkGetCompletionListener extends CompletionListener<BulkGetFuture<?>>
    implements net.spy.memcached.internal.BulkGetCompletionListener {

  public BulkGetCompletionListener(final AgentSpan span, final String methodName) {
    super(span, methodName);
  }

  @Override
  public void onComplete(final BulkGetFuture<?> future) {
    closeAsyncSpan(future);
  }

  @Override
  protected void processResult(final AgentSpan span, final BulkGetFuture<?> future)
      throws ExecutionException, InterruptedException {
    /*
    Note: for now we do not have an affective way of representing results of bulk operations,
    i.e. we cannot say that we got 4 hits out of 10. So we will just ignore results for now.
    */
    future.get();
  }
}
