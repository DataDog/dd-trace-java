package datadog.trace.instrumentation.apachehttpclient;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import datadog.trace.instrumentation.appsec.rasp.modules.NetworkConnectionModule;
import java.net.URI;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.methods.HttpUriRequest;

public class RaspHelperMethods {
  public static void doMethodEnter(final HttpUriRequest request) {
    final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(RaspHelperMethods.class);
    if (callDepth > 0) {
      return;
    }
    NetworkConnectionModule.INSTANCE.onNetworkConnection(request.getURI().toString());
  }

  public static void doMethodEnter(HttpHost httpHost, HttpRequest request) {
    final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(RaspHelperMethods.class);
    if (callDepth > 0) {
      return;
    }
    URI concatedUri = URIUtils.safeConcat(httpHost.toURI(), request.getRequestLine().getUri());
    NetworkConnectionModule.INSTANCE.onNetworkConnection(concatedUri.toString());
  }

  public static void doMethodExit() {
    CallDepthThreadLocalMap.reset(RaspHelperMethods.class);
  }
}
