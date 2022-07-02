package datadog.trace.instrumentation.javahttpclient;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.javahttpclient.JavaHttpClientDecorator.DECORATE;
import static datadog.trace.instrumentation.javahttpclient.JavaHttpClientDecorator.HTTP_REQUEST;

public class JavaHttpClientAdvice {
  public static class SendAdvice {
    private static final Logger LOGGER = LoggerFactory.getLogger(SendAdvice.class);

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(
        @Advice.Argument(value = 0) HttpRequest request) {
      LOGGER.info("Entering SendAdvice methodEnter");
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(HttpClient.class);
      if (callDepth > 0) {
        return null;
      }

      final AgentSpan span = startSpan(HTTP_REQUEST);
      final AgentScope scope = activateSpan(span);

      DECORATE.afterStart(span);
      DECORATE.onRequest(span, request);

      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Return Object result,
        @Advice.Thrown Throwable throwable) {
      LOGGER.info("Entering SendAdvice methodExit");
      if (scope == null) {
        return;
      }
      final AgentSpan span = scope.span();
      try (scope) {
        if (result instanceof HttpResponse) {
          DECORATE.onResponse(span, (HttpResponse<?>) result);
        } // else they probably provided a ResponseHandler.

        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
      } finally {
        span.finish();
        CallDepthThreadLocalMap.reset(HttpClient.class);
      }
    }
  }
}
