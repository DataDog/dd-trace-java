package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.appsec.RaspCallSites;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.PathTraversalModule;
import datadog.trace.instrumentation.appsec.rasp.modules.FileLoadedModule;
import javax.annotation.Nullable;

@Sink(VulnerabilityTypes.PATH_TRAVERSAL)
@CallSite(spi = {IastCallSites.class, RaspCallSites.class})
public class PathCallSite {

  @CallSite.Before("java.nio.file.Path java.nio.file.Path.resolve(java.lang.String)")
  @CallSite.Before("java.nio.file.Path java.nio.file.Path.resolveSibling(java.lang.String)")
  public static void beforeResolve(@CallSite.Argument @Nullable final String other) {
    if (other != null) {
      iastCallback(other);
      raspCallback(other);
    }
  }

  private static void iastCallback(String path) {
    final PathTraversalModule module = InstrumentationBridge.PATH_TRAVERSAL;
    if (module != null) {
      try {
        module.onPathTraversal(path);
      } catch (final Throwable e) {
        module.onUnexpectedException("beforeResolve threw", e);
      }
    }
  }

  private static void raspCallback(String path) {
    FileLoadedModule.INSTANCE.onFileLoaded(path);
  }
}
