package datadog.trace.instrumentation.apachehttpclient5;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.apachehttpclient5.ApacheHttpClientDecorator.DECORATE;
import static datadog.trace.instrumentation.apachehttpclient5.ApacheHttpClientDecorator.HTTP_REQUEST;
import static datadog.trace.instrumentation.apachehttpclient5.HttpHeadersInjectAdapter.SETTER;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;

public class HelperMethods {
  private static final boolean AWS_LEGACY_TRACING =
      Config.get().isLegacyTracingEnabled(false, "aws-sdk");

  public static AgentScope doMethodEnter(final HttpRequest request) {
    boolean awsClientCall = request.containsHeader("amz-sdk-invocation-id");
    if (!AWS_LEGACY_TRACING && awsClientCall) {
      // avoid creating an extra HTTP client span beneath the AWS client call
      return null;
    }

    final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(HttpClient.class);
    if (callDepth > 0) {
      return null;
    }

    return activateHttpSpan(request, awsClientCall);
  }

  public static AgentScope doMethodEnter(HttpHost host, HttpRequest request) {
    boolean awsClientCall = request.containsHeader("amz-sdk-invocation-id");
    if (!AWS_LEGACY_TRACING && awsClientCall) {
      // avoid creating an extra HTTP client span beneath the AWS client call
      return null;
    }

    final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(HttpClient.class);
    if (callDepth > 0) {
      return null;
    }

    return activateHttpSpan(new HostAndRequestAsHttpUriRequest(host, request), awsClientCall);
  }

  private static AgentScope activateHttpSpan(
      final HttpRequest request, final boolean awsClientCall) {
    final AgentSpan span = startSpan(HTTP_REQUEST);
    final AgentScope scope = activateSpan(span);

    DECORATE.afterStart(span);
    DECORATE.onRequest(span, request);

    // AWS calls are often signed, so we can't add headers without breaking the signature.
    if (!awsClientCall) {
      propagate().inject(span, request, SETTER);
      propagate()
          .injectPathwayContext(
              span, request, SETTER, HttpClientDecorator.CLIENT_PATHWAY_EDGE_TAGS);
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
