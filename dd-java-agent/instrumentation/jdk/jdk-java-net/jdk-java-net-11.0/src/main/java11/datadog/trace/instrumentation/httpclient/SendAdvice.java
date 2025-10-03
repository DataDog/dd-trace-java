package datadog.trace.instrumentation.httpclient;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.httpclient.JavaNetClientDecorator.DECORATE;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import net.bytebuddy.asm.Advice;

public class SendAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope methodEnter(@Advice.Argument(value = 0) final HttpRequest httpRequest) {
    try {
      // Here we avoid having the advice applied twice in case we have nested call of this
      // intercepted
      // method.
      // In this particular case, in HttpClientImpl the send method is calling sendAsync under the
      // hood and we do not want to instrument twice.
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(HttpClient.class);
      if (callDepth > 0) {
        return null;
      }
      final AgentSpan span = startSpan(JavaNetClientDecorator.OPERATION_NAME);
      final AgentScope scope = activateSpan(span);

      DECORATE.afterStart(span);
      DECORATE.onRequest(span, httpRequest);

      // propagation is done by another instrumentation since Headers are immutable
      return scope;
    } catch (BlockingException e) {
      CallDepthThreadLocalMap.reset(HttpClient.class);
      // re-throw blocking exceptions
      throw e;
    }
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void methodExit(
      @Advice.Enter final AgentScope scope,
      @Advice.Return final HttpResponse<?> httpResponse,
      @Advice.Thrown final Throwable throwable) {
    if (scope == null) {
      return;
    }
    CallDepthThreadLocalMap.reset(HttpClient.class);

    AgentSpan span = scope.span();
    if (null != throwable) {
      DECORATE.onError(span, throwable);
    } else {
      DECORATE.onResponse(span, httpResponse);
    }
    DECORATE.beforeFinish(span);
    scope.close();
    span.finish();
  }
}
