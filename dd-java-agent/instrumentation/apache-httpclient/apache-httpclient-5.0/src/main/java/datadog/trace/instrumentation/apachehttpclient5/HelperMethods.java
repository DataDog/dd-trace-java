package datadog.trace.instrumentation.apachehttpclient5;

import static datadog.context.Context.current;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.apachehttpclient5.ApacheHttpClientDecorator.DECORATE;
import static datadog.trace.instrumentation.apachehttpclient5.ApacheHttpClientDecorator.HTTP_REQUEST;
import static datadog.trace.instrumentation.apachehttpclient5.HttpHeadersInjectAdapter.SETTER;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;

public class HelperMethods {
  public static AgentScope doMethodEnter(
      final ContextStore<ClassicHttpRequest, Boolean> contextStore,
      final ClassicHttpRequest request) {
    if (Boolean.TRUE.equals(contextStore.get(request))) {
      return null;
    }
    contextStore.put(request, Boolean.TRUE);
    return activateHttpSpan(request);
  }

  public static AgentScope doMethodEnter(
      final ContextStore<ClassicHttpRequest, Boolean> contextStore,
      HttpHost host,
      ClassicHttpRequest request) {
    if (Boolean.TRUE.equals(contextStore.get(request))) {
      return null;
    }
    contextStore.put(request, Boolean.TRUE);
    return activateHttpSpan(new HostAndRequestAsHttpUriRequest(host, request));
  }

  private static AgentScope activateHttpSpan(final HttpRequest request) {
    final AgentSpan span = startSpan(HTTP_REQUEST);
    final AgentScope scope = activateSpan(span);

    DECORATE.afterStart(span);
    DECORATE.onRequest(span, request);

    final boolean awsClientCall = request.containsHeader("amz-sdk-invocation-id");
    // AWS calls are often signed, so we can't add headers without breaking the signature.
    if (!awsClientCall) {
      DECORATE.injectContext(current().with(span), request, SETTER);
    }

    return scope;
  }

  public static void doMethodExit(
      final ContextStore<ClassicHttpRequest, Boolean> contextStore,
      final AgentScope scope,
      final ClassicHttpRequest request,
      final Object result,
      final Throwable throwable) {
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
      contextStore.remove(request);
    }
  }

  public static void onBlockingRequest() {
    CallDepthThreadLocalMap.reset(HttpClient.class);
  }
}
