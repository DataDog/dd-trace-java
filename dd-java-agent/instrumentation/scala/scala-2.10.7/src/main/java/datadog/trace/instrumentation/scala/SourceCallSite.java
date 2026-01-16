package datadog.trace.instrumentation.scala;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.PathTraversalModule;
import datadog.trace.api.iast.sink.SsrfModule;
import java.net.URI;
import javax.annotation.Nullable;

@Sink(VulnerabilityTypes.WEAK_RANDOMNESS)
@CallSite(spi = IastCallSites.class)
public class SourceCallSite {

  @CallSite.Before(
      "scala.io.BufferedSource scala.io.Source$.fromFile(java.lang.String, scala.io.Codec)")
  @CallSite.Before(
      "scala.io.BufferedSource scala.io.Source$.fromFile(java.lang.String, java.lang.String)")
  public static void beforeFromFile(@CallSite.Argument(0) @Nullable final String path) {
    if (path != null) {
      final PathTraversalModule module = InstrumentationBridge.PATH_TRAVERSAL;
      if (module != null) {
        try {
          module.onPathTraversal(path);
        } catch (final Throwable e) {
          module.onUnexpectedException("beforeFromFile threw", e);
        }
      }
    }
  }

  @CallSite.Before(
      "scala.io.BufferedSource scala.io.Source$.fromFile(java.net.URI, scala.io.Codec)")
  @CallSite.Before(
      "scala.io.BufferedSource scala.io.Source$.fromFile(java.net.URI, java.lang.String)")
  @CallSite.Before("scala.io.BufferedSource scala.io.Source$.fromURI(java.net.URI, scala.io.Codec)")
  public static void beforeFromURI(@CallSite.Argument(0) @Nullable final URI path) {
    if (path != null) {
      final PathTraversalModule module = InstrumentationBridge.PATH_TRAVERSAL;
      if (module != null) {
        try {
          module.onPathTraversal(path);
        } catch (final Throwable e) {
          module.onUnexpectedException("beforeFromURI threw", e);
        }
      }
    }
  }

  @CallSite.Before(
      "scala.io.BufferedSource scala.io.Source$.fromURL(java.lang.String, java.lang.String)")
  public static void beforeFromURL(@CallSite.Argument(0) @Nullable final String url) {
    if (url != null) {
      final SsrfModule module = InstrumentationBridge.SSRF;
      if (module != null) {
        try {
          module.onURLConnection(url);
        } catch (final Throwable e) {
          module.onUnexpectedException("beforeFromURL threw", e);
        }
      }
    }
  }
}
