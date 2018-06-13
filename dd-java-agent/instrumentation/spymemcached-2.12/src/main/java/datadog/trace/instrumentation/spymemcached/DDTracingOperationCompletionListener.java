package datadog.trace.instrumentation.spymemcached;

import io.opentracing.Span;
import io.opentracing.Tracer;
import java.util.concurrent.ExecutionException;
import net.spy.memcached.internal.OperationCompletionListener;
import net.spy.memcached.internal.OperationFuture;

public class DDTracingOperationCompletionListener
    extends DDTracingCompletionListener<OperationFuture<? extends Object>>
    implements OperationCompletionListener {
  public DDTracingOperationCompletionListener(Tracer tracer, String methodName) {
    super(tracer, methodName);
  }

  @Override
  public void onComplete(OperationFuture<? extends Object> future) {
    closeSpan(future);
  }

  @Override
  protected void processResult(Span span, OperationFuture<? extends Object> future)
      throws ExecutionException, InterruptedException {
    future.get();
  }
}
