package datadog.trace.instrumentation.java.net;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.appsec.RaspCallSites;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.SsrfModule;
import datadog.trace.instrumentation.appsec.rasp.modules.NetworkConnectionModule;
import java.net.URL;
import javax.annotation.Nonnull;

@CallSite(spi = {IastCallSites.class, RaspCallSites.class})
public class URLSinkCallSite {

  @Sink(VulnerabilityTypes.SSRF)
  @CallSite.Before("java.net.URLConnection java.net.URL.openConnection()")
  @CallSite.Before("java.net.URLConnection java.net.URL.openConnection(java.net.Proxy)")
  @CallSite.Before("java.io.InputStream java.net.URL.openStream()")
  @CallSite.Before("java.lang.Object java.net.URL.getContent()")
  @CallSite.Before("java.lang.Object java.net.URL.getContent(java.lang.Class[])")
  public static void beforeOpenConnection(@CallSite.This final URL url) {
    if (url == null) {
      return;
    }
    iastCallback(url);
    raspCallback(url);
  }

  private static void iastCallback(@Nonnull final URL url) {
    final SsrfModule module = InstrumentationBridge.SSRF;
    if (module != null) {
      try {
        module.onURLConnection(url);
      } catch (final Throwable e) {
        module.onUnexpectedException("After open connection threw", e);
      }
    }
  }

  private static void raspCallback(@Nonnull final URL url) {
    NetworkConnectionModule.INSTANCE.onNetworkConnection(url.toString());
  }
}
