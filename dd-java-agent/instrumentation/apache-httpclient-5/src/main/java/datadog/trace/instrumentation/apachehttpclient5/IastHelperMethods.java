package datadog.trace.instrumentation.apachehttpclient5;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.apachehttpclient5.ApacheHttpClientDecorator.DECORATE;
import static datadog.trace.instrumentation.apachehttpclient5.ApacheHttpClientDecorator.HTTP_REQUEST;
import static datadog.trace.instrumentation.apachehttpclient5.HttpHeadersInjectAdapter.SETTER;

import datadog.trace.api.Config;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.SsrfModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;

import java.net.URISyntaxException;

public class IastHelperMethods {

  public static void doMethodEnter(final HttpRequest request) throws URISyntaxException {
    final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(IastHelperMethods.class);
    if (callDepth > 0) {
      return;
    }
    final SsrfModule module = InstrumentationBridge.SSRF;
    if (module != null) {
      module.onURLConnection(request.getUri());
    }
  }

  public static void doMethodEnter(HttpHost host, HttpRequest request) throws URISyntaxException {

    final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(IastHelperMethods.class);
    if (callDepth > 0) {
      return;
    }
    final SsrfModule module = InstrumentationBridge.SSRF;
    if (module != null) {
      module.onURLConnection(new HostAndRequestAsHttpUriRequest(host, request).getUri().toString());
    }
  }

  public static void doMethodExit() {
    CallDepthThreadLocalMap.reset(IastHelperMethods.class);
  }

}
