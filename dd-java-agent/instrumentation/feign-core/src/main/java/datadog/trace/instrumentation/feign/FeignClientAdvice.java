package datadog.trace.instrumentation.feign;

import static datadog.context.Context.current;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.feign.FeignDecorator.DECORATE;
import static datadog.trace.instrumentation.feign.FeignDecorator.FEIGN_REQUEST;
import static datadog.trace.instrumentation.feign.FeignHeadersInjectAdapter.SETTER;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import feign.Client;
import feign.Request;
import feign.Response;
import net.bytebuddy.asm.Advice;

public class FeignClientAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(@Advice.Argument(0) final Request request) {
    final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Client.class);
    if (callDepth > 0) {
      return null;
    }

    final AgentSpan span = startSpan(FEIGN_REQUEST);
    DECORATE.afterStart(span);
    DECORATE.onRequest(span, request);
    DECORATE.injectContext(current().with(span), request, SETTER);

    return activateSpan(span);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.Enter final AgentScope scope,
      @Advice.Return final Response response,
      @Advice.Thrown final Throwable throwable) {
    if (scope == null) {
      return;
    }
    final AgentSpan span = scope.span();
    try {
      if (response != null) {
        DECORATE.onResponse(span, response);
      }
      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);
    } finally {
      scope.close();
      span.finish();
      CallDepthThreadLocalMap.reset(Client.class);
    }
  }
}
