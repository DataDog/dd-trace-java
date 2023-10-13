package datadog.trace.instrumentation.couchbase_31.client;

import com.couchbase.client.core.cnc.RequestSpan;
import com.couchbase.client.core.error.CouchbaseException;
import com.couchbase.client.core.msg.kv.KeyValueRequest;
import net.bytebuddy.asm.Advice;

public class DefaultErrorUtilAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onExit(
      @Advice.Argument(0) KeyValueRequest<?> request, @Advice.Return CouchbaseException ex) {
    if (null != request) {
      RequestSpan requestSpan = request.requestSpan();
      if (requestSpan instanceof DatadogRequestSpan) {
        ((DatadogRequestSpan) requestSpan).setErrorDirectly(ex);
      }
    }
  }
}
