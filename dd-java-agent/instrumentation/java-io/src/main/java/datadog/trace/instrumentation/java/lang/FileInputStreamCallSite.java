package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.appsec.RaspCallSites;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.PathTraversalModule;
import javax.annotation.Nullable;

@Sink(VulnerabilityTypes.PATH_TRAVERSAL)
@CallSite(
    spi = {IastCallSites.class, RaspCallSites.class},
    helpers = FileLoadedRaspHelper.class)
public class FileInputStreamCallSite {

  @CallSite.Before("void java.io.FileInputStream.<init>(java.lang.String)")
  public static void beforeConstructor(@CallSite.Argument @Nullable final String path) {
    if (path != null) {
      iastCallback(path);
      raspCallback(path);
    }
  }

  private static void iastCallback(String path) {
    final PathTraversalModule module = InstrumentationBridge.PATH_TRAVERSAL;
    if (module != null) {
      try {
        module.onPathTraversal(path);
      } catch (final Throwable e) {
        module.onUnexpectedException("beforeConstructor threw", e);
      }
    }
  }

  private static void raspCallback(String path) {
    FileLoadedRaspHelper.INSTANCE.beforeFileLoaded(path);
  }
}
