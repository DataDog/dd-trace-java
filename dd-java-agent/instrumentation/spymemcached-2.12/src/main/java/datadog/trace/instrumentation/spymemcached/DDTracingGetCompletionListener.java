package datadog.trace.instrumentation.spymemcached;

import io.opentracing.Span;
import io.opentracing.Tracer;
import java.util.concurrent.ExecutionException;
import net.spy.memcached.internal.GetCompletionListener;
import net.spy.memcached.internal.GetFuture;

public class DDTracingGetCompletionListener extends DDTracingCompletionListener<GetFuture<?>>
    implements GetCompletionListener {
  public DDTracingGetCompletionListener(Tracer tracer, String methodName) {
    super(tracer, methodName);
  }

  @Override
  public void onComplete(GetFuture<?> future) {
    closeSpan(future);
  }

  @Override
  protected void processResult(Span span, GetFuture<?> future)
      throws ExecutionException, InterruptedException {
    Object result = future.get();
    setResultTag(span, result != null);
  }
}
