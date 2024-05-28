package datadog.trace.instrumentation.java.net;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.sink.SsrfModule;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import javax.annotation.Nonnull;

@CallSite(spi = IastCallSites.class)
public class URLCallSite {

  @Propagation
  @CallSite.After("void java.net.URL.<init>(java.lang.String)")
  @CallSite.After(
      "void java.net.URL.<init>(java.lang.String, java.lang.String, int, java.lang.String)")
  @CallSite.After(
      "void java.net.URL.<init>(java.lang.String, java.lang.String, int, java.lang.String, java.net.URLStreamHandler)")
  @CallSite.After("void java.net.URL.<init>(java.lang.String, java.lang.String, java.lang.String)")
  @CallSite.After("void java.net.URL.<init>(java.net.URL, java.lang.String)")
  @CallSite.After(
      "void java.net.URL.<init>(java.net.URL, java.lang.String, java.net.URLStreamHandler)")
  public static URL afterCtor(
      @CallSite.AllArguments final Object[] args, @CallSite.Return @Nonnull final URL result) {
    if (args != null && args.length > 0) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        try {
          module.taintObjectIfAnyTainted(result, args);
        } catch (final Throwable e) {
          module.onUnexpectedException("ctor threw", e);
        }
      }
    }
    return result;
  }

  @Propagation
  @CallSite.After("java.lang.String java.net.URL.toString()")
  @CallSite.After("java.lang.String java.net.URL.toExternalForm()")
  public static String afterToString(
      @CallSite.This final URL url, @CallSite.Return final String result) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taintStringIfTainted(result, url);
      } catch (final Throwable e) {
        module.onUnexpectedException("After toString threw", e);
      }
    }
    return result;
  }

  @Propagation
  @CallSite.After("java.net.URI java.net.URL.toURI()")
  public static URI afterToURI(@CallSite.This final URL url, @CallSite.Return final URI result) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taintObjectIfTainted(result, url);
      } catch (final Throwable e) {
        module.onUnexpectedException("After toURI threw", e);
      }
    }
    return result;
  }

  @Sink(VulnerabilityTypes.SSRF)
  @CallSite.Before("java.net.URLConnection java.net.URL.openConnection()")
  public static void beforeOpenConnection(@CallSite.This final URL url) {
    final SsrfModule module = InstrumentationBridge.SSRF;
    if (module != null) {
      try {
        module.onURLConnection(url);
      } catch (final Throwable e) {
        module.onUnexpectedException("After open connection threw", e);
      }
    }
  }

  @Sink(VulnerabilityTypes.SSRF)
  @CallSite.Before("java.net.URLConnection java.net.URL.openConnection(java.net.Proxy)")
  public static void beforeOpenConnection(
      @CallSite.This final URL url, @CallSite.Argument final Proxy proxy) {
    final SsrfModule module = InstrumentationBridge.SSRF;
    if (module != null) {
      try {
        module.onURLConnection(url);
      } catch (final Throwable e) {
        module.onUnexpectedException("After open connection threw", e);
      }
    }
  }

  @Sink(VulnerabilityTypes.SSRF)
  @CallSite.Before("java.io.InputStream java.net.URL.openStream()")
  public static void beforeOpenStream(@CallSite.This final URL url) {
    final SsrfModule module = InstrumentationBridge.SSRF;
    if (module != null) {
      try {
        module.onURLConnection(url);
      } catch (final Throwable e) {
        module.onUnexpectedException("After open connection threw", e);
      }
    }
  }
}
