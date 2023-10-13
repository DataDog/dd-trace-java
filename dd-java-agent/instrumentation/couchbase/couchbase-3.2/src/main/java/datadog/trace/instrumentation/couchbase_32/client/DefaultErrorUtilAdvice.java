package datadog.trace.instrumentation.couchbase_32.client;

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

  // Use this to not apply instrumentation on [2,3.2)
  private static void muzzleCheck(RequestSpan requestSpan) {
    requestSpan.status(RequestSpan.StatusCode.ERROR);
  }
}
