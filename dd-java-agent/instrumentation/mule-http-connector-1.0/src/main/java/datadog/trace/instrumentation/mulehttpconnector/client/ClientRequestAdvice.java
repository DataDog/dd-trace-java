package datadog.trace.instrumentation.mulehttpconnector.client;

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHandler;
import com.ning.http.client.Request;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.mulehttpconnector.client.ClientDecorator.DECORATE;
import static datadog.trace.instrumentation.mulehttpconnector.client.InjectAdapter.SETTER;

public class ClientRequestAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onEnter(
      @Advice.This final Object source,
      @Advice.Argument(0) final Request request,
      @Advice.Argument(1) final AsyncHandler<?> handler) {
    final AgentSpan span = startSpan("http.request");
    final AgentScope scope = activateSpan(span, false);

    DECORATE.afterStart(span);
    DECORATE.onRequest(span, request);
    propagate().inject(span, request, SETTER);

    InstrumentationContext.get(AsyncCompletionHandler.class, AgentSpan.class)
        .put((AsyncCompletionHandler) handler, span);
    scope.close();
  }
}
