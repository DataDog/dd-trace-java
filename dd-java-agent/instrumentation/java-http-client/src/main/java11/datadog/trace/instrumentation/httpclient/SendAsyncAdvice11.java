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

public class SendAsyncAdvice11 {

  public static Object[] doMethodEnter(
      final HttpRequest httpRequest, HttpResponse.BodyHandler<?> bodyHandler) {
    final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(HttpClient.class);
    if (callDepth > 0) {
      return new Object[] {null, null};
    }
    final AgentSpan span = AgentTracer.startSpan(JavaNetClientDecorator.OPERATION_NAME);
    final AgentScope scope = activateSpan(span, true);
    if (bodyHandler != null) {
      bodyHandler = new BodyHandlerWrapper<>(bodyHandler, captureSpan(span));
    }

    DECORATE.afterStart(span);
    DECORATE.onRequest(span, httpRequest);

    // propagate().inject(span, request, SETTER);

    return new Object[] {bodyHandler, scope};
  }

  public static Object[] methodEnter(final Object httpRequest, Object bodyHandler) {
    return doMethodEnter((HttpRequest) httpRequest, (HttpResponse.BodyHandler<?>) bodyHandler);
  }

  private static Object doMethodExit(
      final AgentScope scope,
      final HttpRequest httpRequest,
      CompletableFuture<HttpResponse<?>> future,
      final Throwable throwable) {
    if (scope == null) {
      return null;
    }
    CallDepthThreadLocalMap.reset(HttpClient.class);

    AgentSpan span = scope.span();
    if (throwable != null) {
      DECORATE.onError(scope.span(), throwable);
    } else {
      future = future.whenComplete(new ResponseConsumer(span, httpRequest));
    }
    scope.close();
    return future;
  }

  public static Object methodExit(
      final AgentScope scope, final Object httpRequest, Object future, final Throwable throwable) {
    return doMethodExit(
        scope, (HttpRequest) httpRequest, (CompletableFuture<HttpResponse<?>>) future, throwable);
  }
}
