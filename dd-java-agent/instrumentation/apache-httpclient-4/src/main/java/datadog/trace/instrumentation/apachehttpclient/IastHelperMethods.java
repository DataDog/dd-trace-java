package datadog.trace.instrumentation.apachehttpclient;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.SsrfModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import org.apache.http.HttpHost;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpUriRequest;

public class IastHelperMethods {
  public static void doMethodEnter(final HttpUriRequest request) {
    final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(HttpClient.class);
    if (callDepth > 1) {
      return;
    }
    final SsrfModule module = InstrumentationBridge.SSRF;
    if (module != null) {
      module.onURLConnection(request.getURI());
    }
  }

  public static void doMethodEnter(HttpHost host) {
    final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(HttpClient.class);
    if (callDepth > 1) {
      return;
    }
    final SsrfModule module = InstrumentationBridge.SSRF;
    if (module != null) {
      module.onURLConnection(host.toURI());
    }
  }

  public static void doMethodExit() {
    CallDepthThreadLocalMap.reset(HttpClient.class);
  }
}
