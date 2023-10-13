package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.PathTraversalModule;
import java.net.URI;
import javax.annotation.Nullable;

@Sink(VulnerabilityTypes.PATH_TRAVERSAL)
@CallSite(spi = IastCallSites.class)
public class PathsCallSite {

  @CallSite.Before(
      "java.nio.file.Path java.nio.file.Paths.get(java.lang.String, java.lang.String[])")
  public static void beforeGet(
      @CallSite.Argument @Nullable final String first,
      @CallSite.Argument @Nullable final String[] more) {
    if (first != null && more != null) { // both parameters should be not null
      final PathTraversalModule module = InstrumentationBridge.PATH_TRAVERSAL;
      if (module != null) {
        try {
          module.onPathTraversal(first, more);
        } catch (final Throwable e) {
          module.onUnexpectedException("beforeGet threw", e);
        }
      }
    }
  }

  @CallSite.Before("java.nio.file.Path java.nio.file.Paths.get(java.net.URI)")
  public static void beforeGet(@CallSite.Argument @Nullable final URI uri) {
    if (uri != null) {
      final PathTraversalModule module = InstrumentationBridge.PATH_TRAVERSAL;
      if (module != null) {
        try {
          module.onPathTraversal(uri);
        } catch (final Throwable e) {
          module.onUnexpectedException("beforeGet threw", e);
        }
      }
    }
  }
}
