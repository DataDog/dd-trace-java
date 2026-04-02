package datadog.trace.instrumentation.apachehttpclient5;

import static datadog.context.Context.current;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.apachehttpclient5.ApacheHttpClientDecorator.DECORATE;
import static datadog.trace.instrumentation.apachehttpclient5.ApacheHttpClientDecorator.HTTP_REQUEST;
import static datadog.trace.instrumentation.apachehttpclient5.HttpHeadersInjectAdapter.SETTER;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;

public class HelperMethods {

  public static AgentScope doMethodEnter(
      final ContextStore<ClassicHttpRequest, Boolean> instrumentationMarker,
      final ClassicHttpRequest request) {
    if (testAndSet(instrumentationMarker, request)) {
      return null;
    }
    return activateHttpSpan(request);
  }

  public static AgentScope doMethodEnter(
      final ContextStore<ClassicHttpRequest, Boolean> instrumentationMarker,
      HttpHost host,
      final ClassicHttpRequest request) {
    if (testAndSet(instrumentationMarker, request)) {
      return null;
    }
    return activateHttpSpan(new HostAndRequestAsHttpUriRequest(host, request));
  }

  // Checks current value in context store,
  // and ensures it's set to true when this method exists.
  // We are using a contextStore rather than a CallDepthMap because "sub-requests" can be triggered
  // by interceptors (see ApacheClientNestedExecuteTest).
  private static boolean testAndSet(
      final ContextStore<ClassicHttpRequest, Boolean> instrumentationMarker,
      final ClassicHttpRequest request) {
    if (request == null) {
      // we probably don't want to instrument a call with a null request ?
      return true;
    }
    Boolean instrumented = instrumentationMarker.get(request);
    if (Boolean.TRUE.equals(instrumented)) {
      return true;
    }
    instrumentationMarker.put(request, Boolean.TRUE);
    return false;
  }

  private static AgentScope activateHttpSpan(final HttpRequest request) {
    final AgentSpan span = startSpan(HTTP_REQUEST);
    final AgentScope scope = activateSpan(span);

    DECORATE.afterStart(span);
    DECORATE.onRequest(span, request);

    return scope;
  }

  public static void doInjectContext(final HttpRequest request) {
    if (request.containsHeader("amz-sdk-invocation-id")) {
      return;
    }
    DECORATE.injectContext(current(), request, SETTER);
  }

  public static void doMethodExit(
      final ContextStore<ClassicHttpRequest, Boolean> instrumentationMarker,
      final ClassicHttpRequest request,
      final AgentScope scope,
      final Object result,
      final Throwable throwable) {
    if (scope == null) {
      return;
    }
    try {
      final AgentSpan span = scope.span();
      if (result instanceof HttpResponse) {
        DECORATE.onResponse(span, (HttpResponse) result);
      } // else they probably provided a ResponseHandler.

      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);
    } finally {
      AgentSpan span = scope.span();
      scope.close();
      span.finish();
      instrumentationMarker.remove(request);
    }
  }
}
