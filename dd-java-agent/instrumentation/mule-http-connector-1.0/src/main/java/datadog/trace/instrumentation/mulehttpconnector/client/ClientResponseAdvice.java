package datadog.trace.instrumentation.mulehttpconnector.client;

import com.ning.http.client.Response;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.mule.service.http.impl.service.client.async.ResponseAsyncHandler;

import static datadog.trace.instrumentation.mulehttpconnector.client.ClientDecorator.DECORATE;

public class ClientResponseAdvice {

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void stopSpan(
      @Advice.This final ResponseAsyncHandler handler,
      @Advice.Argument(0) final Response response) {
    final ContextStore<ResponseAsyncHandler, AgentSpan> contextStore =
        InstrumentationContext.get(ResponseAsyncHandler.class, AgentSpan.class);
    final AgentSpan span = contextStore.get(handler);
    if (span != null) {
      DECORATE.afterStart(span);
      DECORATE.onResponse(span, response);

      DECORATE.beforeFinish(span);
      span.finish();
    }
  }
}
