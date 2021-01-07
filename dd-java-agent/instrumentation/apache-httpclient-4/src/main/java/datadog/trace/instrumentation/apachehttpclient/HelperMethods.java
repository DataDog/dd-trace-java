package datadog.trace.instrumentation.apachehttpclient;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.apachehttpclient.ApacheHttpClientDecorator.DECORATE;
import static datadog.trace.instrumentation.apachehttpclient.ApacheHttpClientDecorator.HTTP_REQUEST;
import static datadog.trace.instrumentation.apachehttpclient.HttpHeadersInjectAdapter.SETTER;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpUriRequest;

public class HelperMethods {
  public static AgentScope doMethodEnter(final HttpUriRequest request) {
    final AgentSpan span = startSpan(HTTP_REQUEST);
    span.setMeasured(true);
    final AgentScope scope = activateSpan(span);

    DECORATE.afterStart(span);
    DECORATE.onRequest(span, request);

    final boolean awsClientCall = request.getHeaders("amz-sdk-invocation-id").length > 0;
    // AWS calls are often signed, so we can't add headers without breaking the signature.
    if (!awsClientCall) {
      propagate().inject(span, request, SETTER);
    }
    return scope;
  }

  public static void doMethodExit(
      final AgentScope scope, final Object result, final Throwable throwable) {
    if (scope == null) {
      return;
    }
    final AgentSpan span = scope.span();
    try {
      if (result instanceof HttpResponse) {
        DECORATE.onResponse(span, (HttpResponse) result);
      } // else they probably provided a ResponseHandler.

      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);
    } finally {
      scope.close();
      span.finish();
      CallDepthThreadLocalMap.reset(HttpClient.class);
    }
  }
}
