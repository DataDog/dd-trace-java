package datadog.trace.instrumentation.spymemcached;

import io.opentracing.Span;
import io.opentracing.Tracer;
import java.util.concurrent.ExecutionException;
import net.spy.memcached.internal.*;

public class DDTracingBulkCompletionListener extends DDTracingCompletionListener<BulkGetFuture<?>>
    implements BulkGetCompletionListener {
  public DDTracingBulkCompletionListener(Tracer tracer, String methodName) {
    super(tracer, methodName);
  }

  @Override
  public void onComplete(BulkGetFuture<?> future) {
    closeSpan(future);
  }

  @Override
  protected void processResult(Span span, BulkGetFuture<?> future)
      throws ExecutionException, InterruptedException {
    /*
    Note: for now we do not have an affective way of representing results of bulk operations,
    i.e. we cannot day that we got 4 hits out of 10. So we will just ignore results for now.
    */
    future.get();
  }
}
