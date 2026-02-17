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
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;

public class HelperMethods {

  public static AgentScope doMethodEnter(
      final ContextStore<HttpRequest, Integer> depthStore, final HttpRequest request) {
    int depth = incrementDepth(depthStore, request);
    if (depth > 1) {
      return null;
    }
    return activateHttpSpan(request);
  }

  public static AgentScope doMethodEnter(
      final ContextStore<HttpRequest, Integer> depthStore,
      HttpHost host,
      final HttpRequest request) {
    int depth = incrementDepth(depthStore, request);
    if (depth > 1) {
      return null;
    }
    return activateHttpSpan(new HostAndRequestAsHttpUriRequest(host, request));
  }

  private static int incrementDepth(
      final ContextStore<HttpRequest, Integer> depthStore, final HttpRequest request) {
    Integer depth = depthStore.get(request);
    depth = depth == null ? 1 : (depth + 1);
    depthStore.put(request, depth);
    return depth;
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
      final ContextStore<HttpRequest, Integer> depthStore,
      final HttpRequest request,
      final AgentScope scope,
      final Object result,
      final Throwable throwable) {
    try {
      if (scope == null) {
        return;
      }
      final AgentSpan span = scope.span();
      if (result instanceof HttpResponse) {
        DECORATE.onResponse(span, (HttpResponse) result);
      } // else they probably provided a ResponseHandler.

      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);
    } finally {
      if (scope != null) {
        AgentSpan span = scope.span();
        scope.close();
        span.finish();
      }
      Integer depth = depthStore.get(request);
      if (depth != null && depth > 0) {
        depthStore.put(request, depth - 1);
      }
    }
  }

  /**
   * Cleans up state when a BlockingException is thrown from methodEnter. Since the exception
   * unwinds the whole stack, we this request's depth to 0.
   */
  public static void onBlockingRequest(
      final ContextStore<HttpRequest, Integer> depthStore, final HttpRequest request) {
    depthStore.put(request, 0);
  }
}
