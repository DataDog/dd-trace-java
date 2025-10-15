package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.appsec.RaspCallSites;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.PathTraversalModule;
import java.io.File;
import java.net.URI;
import javax.annotation.Nullable;

@Sink(VulnerabilityTypes.PATH_TRAVERSAL)
@CallSite(
    spi = {IastCallSites.class, RaspCallSites.class},
    helpers = FileLoadedRaspHelper.class)
public class FileCallSite {

  @CallSite.Before("void java.io.File.<init>(java.lang.String)")
  public static void beforeConstructor(@CallSite.Argument @Nullable final String path) {
    if (path != null) { // new File(null) throws NPE
      iastCallback(path);
      raspCallback(path);
    }
  }

  @CallSite.Before("void java.io.File.<init>(java.lang.String, java.lang.String)")
  public static void beforeConstructor(
      @CallSite.Argument @Nullable final String parent,
      @CallSite.Argument @Nullable final String child) {
    if (child != null) { // new File("abc", null) throws NPE
      final PathTraversalModule module = InstrumentationBridge.PATH_TRAVERSAL;
      iastCallback(parent, child);
      raspCallback(parent, child);
    }
  }

  @CallSite.Before("void java.io.File.<init>(java.io.File, java.lang.String)")
  public static void beforeConstructor(
      @CallSite.Argument @Nullable final File parent,
      @CallSite.Argument @Nullable final String child) {
    if (child != null) { // new File(parent, null) throws NPE
      final PathTraversalModule module = InstrumentationBridge.PATH_TRAVERSAL;
      iastCallback(parent, child);
      raspCallback(parent, child);
    }
  }

  @CallSite.Before("void java.io.File.<init>(java.net.URI)")
  public static void beforeConstructor(@CallSite.Argument @Nullable final URI uri) {
    if (uri != null) {
      final PathTraversalModule module = InstrumentationBridge.PATH_TRAVERSAL;
      iastCallback(uri);
      raspCallback(uri);
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

  private static void iastCallback(File parent, String child) {
    final PathTraversalModule module = InstrumentationBridge.PATH_TRAVERSAL;
    if (module != null) {
      try {
        module.onPathTraversal(parent, child);
      } catch (final Throwable e) {
        module.onUnexpectedException("beforeConstructor threw", e);
      }
    }
  }

  private static void iastCallback(String parent, String child) {
    final PathTraversalModule module = InstrumentationBridge.PATH_TRAVERSAL;
    if (module != null) {
      try {
        module.onPathTraversal(parent, child);
      } catch (final Throwable e) {
        module.onUnexpectedException("beforeConstructor threw", e);
      }
    }
  }

  private static void iastCallback(URI uri) {
    final PathTraversalModule module = InstrumentationBridge.PATH_TRAVERSAL;
    if (module != null) {
      try {
        module.onPathTraversal(uri);
      } catch (final Throwable e) {
        module.onUnexpectedException("beforeConstructor threw", e);
      }
    }
  }

  private static void raspCallback(File parent, String child) {
    FileLoadedRaspHelper.INSTANCE.beforeFileLoaded(parent, child);
  }

  private static void raspCallback(String parent, String file) {
    FileLoadedRaspHelper.INSTANCE.beforeFileLoaded(parent, file);
  }

  private static void raspCallback(String s) {
    FileLoadedRaspHelper.INSTANCE.beforeFileLoaded(s);
  }

  private static void raspCallback(URI uri) {
    FileLoadedRaspHelper.INSTANCE.beforeFileLoaded(uri);
  }
}
