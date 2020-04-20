package datadog.trace.instrumentation.mulehttpconnector.client;

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.Response;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.mulehttpconnector.client.ClientDecorator.DECORATE;

public class ClientResponseAdvice {

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.This final AsyncCompletionHandler handler,
      @Advice.Argument(0) final Response response,
      @Advice.Thrown final Throwable throwable) {

    final ContextStore<AsyncCompletionHandler, AgentSpan> contextStore =
        InstrumentationContext.get(AsyncCompletionHandler.class, AgentSpan.class);
    final AgentSpan span = contextStore.get(handler);
    final AgentScope scope = activateSpan(span, true);

    if (span != null) {
      contextStore.put(handler, null);
      if (throwable == null) {
        DECORATE.onResponse(span, response);
      } else {
        DECORATE.onError(span, throwable);
      }
      DECORATE.beforeFinish(span);
      span.finish();
    }
    scope.close();
  }
}
