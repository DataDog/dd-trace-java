package datadog.trace.instrumentation.apachehttpclient;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.SsrfModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import java.net.URI;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.methods.HttpUriRequest;

public class IastHelperMethods {
  public static void doMethodEnter(final HttpUriRequest request) {
    final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(IastHelperMethods.class);
    if (callDepth > 0) {
      return;
    }
    final SsrfModule module = InstrumentationBridge.SSRF;
    if (module != null) {
      module.onURLConnection(request.getURI());
    }
  }

  public static void doMethodEnter(HttpHost httpHost, HttpRequest request) {
    final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(IastHelperMethods.class);
    if (callDepth > 0) {
      return;
    }
    final SsrfModule ssrfModule = InstrumentationBridge.SSRF;
    if (ssrfModule != null) {
      URI concatedUri = URIUtils.safeConcat(httpHost.toURI(), request.getRequestLine().getUri());
      ssrfModule.onURLConnection(
          concatedUri.toString(), httpHost.toURI(), request.getRequestLine().getUri());
    }
  }

  public static void doMethodExit() {
    CallDepthThreadLocalMap.reset(IastHelperMethods.class);
  }
}
