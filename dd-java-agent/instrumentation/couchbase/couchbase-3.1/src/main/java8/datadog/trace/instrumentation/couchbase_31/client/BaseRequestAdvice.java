package datadog.trace.instrumentation.couchbase_31.client;

import com.couchbase.client.core.cnc.RequestSpan;
import datadog.trace.bootstrap.instrumentation.api8.java.concurrent.StatusSettingCompletableFuture;
import java.util.concurrent.CompletableFuture;
import net.bytebuddy.asm.Advice;

public class BaseRequestAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onExit(
      @Advice.FieldValue(value = "response", readOnly = false) CompletableFuture<?> response,
      @Advice.FieldValue(value = "requestSpan") RequestSpan requestSpan) {
    if (requestSpan instanceof DatadogRequestSpan) {
      response = new StatusSettingCompletableFuture<>((DatadogRequestSpan) requestSpan);
    }
  }
}
