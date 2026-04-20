package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.appsec.RaspCallSites;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.PathTraversalModule;
import java.nio.file.Path;
import javax.annotation.Nullable;

@Sink(VulnerabilityTypes.PATH_TRAVERSAL)
@CallSite(
    spi = {IastCallSites.class, RaspCallSites.class},
    helpers = FileIORaspHelper.class)
public class PathCallSite {

  @CallSite.Before("java.nio.file.Path java.nio.file.Path.resolve(java.lang.String)")
  @CallSite.Before("java.nio.file.Path java.nio.file.Path.resolveSibling(java.lang.String)")
  public static void beforeResolve(@CallSite.Argument @Nullable final String other) {
    if (other != null) {
      iastCallback(other);
      raspCallback(other);
    }
  }

  @CallSite.Before("java.nio.file.Path java.nio.file.Path.resolve(java.nio.file.Path)")
  @CallSite.Before("java.nio.file.Path java.nio.file.Path.resolveSibling(java.nio.file.Path)")
  public static void beforeResolveWithPath(@CallSite.Argument @Nullable final Path other) {
    if (other != null) {
      raspCallback(other.toString());
    }
  }

  private static void iastCallback(String other) {
    final PathTraversalModule module = InstrumentationBridge.PATH_TRAVERSAL;
    if (module != null) {
      try {
        module.onPathTraversal(other);
      } catch (final Throwable e) {
        module.onUnexpectedException("beforeResolve threw", e);
      }
    }
  }

  private static void raspCallback(String other) {
    FileIORaspHelper.INSTANCE.beforeFileLoaded(other);
  }
}
