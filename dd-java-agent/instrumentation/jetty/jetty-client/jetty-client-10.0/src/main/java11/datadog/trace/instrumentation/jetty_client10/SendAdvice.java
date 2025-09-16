package datadog.trace.instrumentation.jetty_client10;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getCurrentContext;
import static datadog.trace.instrumentation.jetty_client.HeadersInjectAdapter.SETTER;
import static datadog.trace.instrumentation.jetty_client10.JettyClientDecorator.DECORATE;
import static datadog.trace.instrumentation.jetty_client10.JettyClientDecorator.HTTP_REQUEST;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;

public class SendAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentSpan methodEnter(
      @Advice.Argument(0) Request request,
      @Advice.Argument(1) List<Response.ResponseListener> responseListeners) {
    AgentSpan span = startSpan("jetty-client", HTTP_REQUEST);
    InstrumentationContext.get(Request.class, AgentSpan.class).put(request, span);
    // make sure the span is finished before onComplete callbacks execute
    responseListeners.add(0, new SpanFinishingCompleteListener(span));
    DECORATE.afterStart(span);
    DECORATE.onRequest(span, request);
    DECORATE.injectContext(getCurrentContext().with(span), request, SETTER);
    return span;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void methodExit(
      @Advice.Enter final AgentSpan span, @Advice.Thrown final Throwable throwable) {
    if (throwable != null) {
      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }
}
