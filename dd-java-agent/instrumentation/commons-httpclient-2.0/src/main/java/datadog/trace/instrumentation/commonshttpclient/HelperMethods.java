package datadog.trace.instrumentation.commonshttpclient;

import static datadog.context.Context.current;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.commonshttpclient.CommonsHttpClientDecorator.DECORATE;
import static datadog.trace.instrumentation.commonshttpclient.CommonsHttpClientDecorator.HTTP_REQUEST;
import static datadog.trace.instrumentation.commonshttpclient.HttpHeadersInjectAdapter.SETTER;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;

public class HelperMethods {

  public static AgentScope doMethodEnter(final HttpMethod method) {
    final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(HttpClient.class);
    if (callDepth > 0) {
      return null;
    }

    final AgentSpan span =
        startSpan(CommonsHttpClientDecorator.COMMONS_HTTP_CLIENT.toString(), HTTP_REQUEST);
    DECORATE.afterStart(span);
    DECORATE.onRequest(span, method);

    final AgentScope scope = activateSpan(span);

    DECORATE.injectContext(current(), method, SETTER);

    return scope;
  }

  public static void doMethodExit(
      final AgentScope scope, final HttpMethod method, final Throwable throwable) {
    if (scope == null) {
      return;
    }
    final AgentSpan span = scope.span();
    try {
      if (throwable != null) {
        DECORATE.onError(span, throwable);
      } else {
        DECORATE.onResponse(span, method);
      }
      DECORATE.beforeFinish(span);
    } finally {
      scope.close();
      span.finish();
      CallDepthThreadLocalMap.reset(HttpClient.class);
    }
  }
}
