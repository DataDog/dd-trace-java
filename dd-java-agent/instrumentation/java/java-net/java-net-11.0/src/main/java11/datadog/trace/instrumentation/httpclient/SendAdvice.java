package datadog.trace.instrumentation.httpclient;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromScope;
import static datadog.trace.instrumentation.httpclient.JavaNetClientDecorator.DECORATE;
import static datadog.trace.instrumentation.httpclient.JavaNetClientDecorator.INSTRUMENTATION_NAME;
import static datadog.trace.instrumentation.httpclient.JavaNetClientDecorator.OPERATION_NAME;

import datadog.appsec.api.blocking.BlockingException;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import net.bytebuddy.asm.Advice;

public class SendAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static ContextScope methodEnter(
      @Advice.Argument(value = 0) final HttpRequest httpRequest) {
    ContextScope scope = null;
    try {
      if (DECORATE.isAgentRequest(httpRequest)) {
        return null;
      }
      // Here we avoid having the advice applied twice in case we have nested call of this
      // intercepted method.
      // In this particular case, in HttpClientImpl the send method is calling sendAsync under the
      // hood, and we do not want to instrument twice.
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(HttpClient.class);
      if (callDepth > 0) {
        return null;
      }
      DECORATE.allowContextInjection();
      final AgentSpan span = startSpan(INSTRUMENTATION_NAME, OPERATION_NAME);
      scope = activateSpan(span);

      DECORATE.afterStart(span);
      DECORATE.onRequest(span, httpRequest);

      // propagation is done by another instrumentation since Headers are immutable
      return scope;
    } catch (BlockingException e) {
      CallDepthThreadLocalMap.reset(HttpClient.class);
      DECORATE.blockContextInjection();
      if (scope != null) {
        final AgentSpan span = spanFromScope(scope);
        try {
          DECORATE.onError(span, e);
          DECORATE.beforeFinish(span);
        } finally {
          scope.close();
          span.finish();
        }
      }
      // re-throw blocking exceptions
      throw e;
    }
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void methodExit(
      @Advice.Enter final ContextScope scope,
      @Advice.Return final HttpResponse<?> httpResponse,
      @Advice.Thrown final Throwable throwable) {
    if (scope == null) {
      return;
    }
    CallDepthThreadLocalMap.reset(HttpClient.class);
    DECORATE.blockContextInjection();

    AgentSpan span = spanFromScope(scope);
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
