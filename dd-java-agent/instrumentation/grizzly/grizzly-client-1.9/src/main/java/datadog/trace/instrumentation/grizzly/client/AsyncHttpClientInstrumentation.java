package datadog.trace.instrumentation.grizzly.client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getCurrentContext;
import static datadog.trace.instrumentation.grizzly.client.ClientDecorator.DECORATE;
import static datadog.trace.instrumentation.grizzly.client.ClientDecorator.HTTP_REQUEST;
import static datadog.trace.instrumentation.grizzly.client.InjectAdapter.SETTER;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.Request;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

public final class AsyncHttpClientInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "com.ning.http.client.AsyncHttpClient";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("executeRequest")
            .and(takesArgument(0, named("com.ning.http.client.Request")))
            .and(takesArgument(1, named("com.ning.http.client.AsyncHandler")))
            .and(isPublic()),
        getClass().getName() + "$ExecuteRequest");
  }

  public static class ExecuteRequest {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(0) final Request request,
        @Advice.Argument(value = 1, readOnly = false) AsyncHandler<?> handler) {
      AgentSpan parentSpan = activeSpan();
      AgentSpan span = startSpan(HTTP_REQUEST);
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, request);
      DECORATE.injectContext(getCurrentContext().with(span), request, SETTER);
      handler = new AsyncHandlerAdapter<>(span, parentSpan, handler);
    }
  }
}
