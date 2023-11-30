package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.PathTraversalModule;
import javax.annotation.Nullable;

@Sink(VulnerabilityTypes.PATH_TRAVERSAL)
@CallSite(spi = IastCallSites.class)
public class FileOutputStreamCallSite {

  @CallSite.Before("void java.io.FileOutputStream.<init>(java.lang.String)")
  @CallSite.Before("void java.io.FileOutputStream.<init>(java.lang.String, boolean)")
  public static void beforeConstructor(@CallSite.Argument(0) @Nullable final String path) {
    if (path != null) {
      final PathTraversalModule module = InstrumentationBridge.PATH_TRAVERSAL;
      if (module != null) {
        try {
          module.onPathTraversal(path);
        } catch (final Throwable e) {
          module.onUnexpectedException("beforeConstructor threw", e);
        }
      }
    }
  }
}
