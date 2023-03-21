package datadog.trace.instrumentation.httpclient;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureSpan;
import static datadog.trace.instrumentation.httpclient.JavaNetClientDecorator.DECORATE;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import net.bytebuddy.asm.Advice;

public class SendAsyncAdvice11 {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope methodEnter(
      @Advice.Argument(value = 0) final HttpRequest httpRequest,
      @Advice.Argument(value = 1, readOnly = false) HttpResponse.BodyHandler<?> bodyHandler) {
    final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(HttpClient.class);
    if (callDepth > 0) {
      return null;
    }
    final AgentSpan span = AgentTracer.startSpan(JavaNetClientDecorator.OPERATION_NAME);
    final AgentScope scope = activateSpan(span, true);
    if (bodyHandler != null) {
      bodyHandler = new BodyHandlerWrapper<>(bodyHandler, captureSpan(span));
    }

    DECORATE.afterStart(span);
    DECORATE.onRequest(span, httpRequest);

    // propagation is done by another instrumentation since Headers are immutable
    return scope;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void methodExit(
      @Advice.Enter final AgentScope scope,
      @Advice.Argument(value = 0) final HttpRequest httpRequest,
      @Advice.Return(readOnly = false) CompletableFuture<HttpResponse<?>> future,
      @Advice.Thrown final Throwable throwable) {
    if (scope == null) {
      return;
    }
    CallDepthThreadLocalMap.reset(HttpClient.class);

    AgentSpan span = scope.span();
    if (throwable != null) {
      DECORATE.onError(scope.span(), throwable);
    } else {
      future = future.whenComplete(new ResponseConsumer(span, httpRequest));
    }
    scope.close();
  }
}
