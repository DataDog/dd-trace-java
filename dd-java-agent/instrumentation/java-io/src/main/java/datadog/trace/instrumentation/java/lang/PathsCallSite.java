package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.appsec.RaspCallSites;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.PathTraversalModule;
import datadog.trace.instrumentation.appsec.rasp.modules.FileLoadedModule;
import java.net.URI;
import java.nio.file.FileSystems;
import javax.annotation.Nullable;

@Sink(VulnerabilityTypes.PATH_TRAVERSAL)
@CallSite(spi = {IastCallSites.class, RaspCallSites.class})
public class PathsCallSite {

  @CallSite.Before(
      "java.nio.file.Path java.nio.file.Paths.get(java.lang.String, java.lang.String[])")
  public static void beforeGet(
      @CallSite.Argument @Nullable final String first,
      @CallSite.Argument @Nullable final String[] more) {
    if (first != null && more != null) { // both parameters should be not null
      iastCallback(first, more);
      raspCallback(first, more);
    }
  }

  @CallSite.Before("java.nio.file.Path java.nio.file.Paths.get(java.net.URI)")
  public static void beforeGet(@CallSite.Argument @Nullable final URI uri) {
    if (uri != null) {
      iastCallback(uri);
      raspCallback(uri.toString());
    }
  }

  private static void iastCallback(URI uri) {
    final PathTraversalModule module = InstrumentationBridge.PATH_TRAVERSAL;
    if (module != null) {
      try {
        module.onPathTraversal(uri);
      } catch (final Throwable e) {
        module.onUnexpectedException("beforeGet threw", e);
      }
    }
  }

  private static void iastCallback(String first, String[] more) {
    final PathTraversalModule module = InstrumentationBridge.PATH_TRAVERSAL;
    if (module != null) {
      try {
        module.onPathTraversal(first, more);
      } catch (final Throwable e) {
        module.onUnexpectedException("beforeGet threw", e);
      }
    }
  }

  private static void raspCallback(String uriString) {
    FileLoadedModule.INSTANCE.onFileLoaded(uriString);
  }

  private static void raspCallback(String first, String[] more) {
    String separator = FileSystems.getDefault().getSeparator();
    String path = first + separator + String.join(separator, more);
    FileLoadedModule.INSTANCE.onFileLoaded(path);
  }
}
