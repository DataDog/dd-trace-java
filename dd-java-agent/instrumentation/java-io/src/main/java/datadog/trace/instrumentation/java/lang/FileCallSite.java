package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.PathTraversalModule;
import java.io.File;
import java.net.URI;
import javax.annotation.Nullable;

@Sink(VulnerabilityTypes.PATH_TRAVERSAL)
@CallSite(spi = IastCallSites.class)
public class FileCallSite {

  @CallSite.Before("void java.io.File.<init>(java.lang.String)")
  public static void beforeConstructor(@CallSite.Argument @Nullable final String path) {
    if (path != null) { // new File(null) throws NPE
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

  @CallSite.Before("void java.io.File.<init>(java.lang.String, java.lang.String)")
  public static void beforeConstructor(
      @CallSite.Argument @Nullable final String parent,
      @CallSite.Argument @Nullable final String child) {
    if (child != null) { // new File("abc", null) throws NPE
      final PathTraversalModule module = InstrumentationBridge.PATH_TRAVERSAL;
      if (module != null) {
        try {
          module.onPathTraversal(parent, child);
        } catch (final Throwable e) {
          module.onUnexpectedException("beforeConstructor threw", e);
        }
      }
    }
  }

  @CallSite.Before("void java.io.File.<init>(java.io.File, java.lang.String)")
  public static void beforeConstructor(
      @CallSite.Argument @Nullable final File parent,
      @CallSite.Argument @Nullable final String child) {
    if (child != null) { // new File(parent, null) throws NPE
      final PathTraversalModule module = InstrumentationBridge.PATH_TRAVERSAL;
      if (module != null) {
        try {
          module.onPathTraversal(parent, child);
        } catch (final Throwable e) {
          module.onUnexpectedException("beforeConstructor threw", e);
        }
      }
    }
  }

  @CallSite.Before("void java.io.File.<init>(java.net.URI)")
  public static void beforeConstructor(@CallSite.Argument @Nullable final URI uri) {
    if (uri != null) {
      final PathTraversalModule module = InstrumentationBridge.PATH_TRAVERSAL;
      if (module != null) {
        try {
          module.onPathTraversal(uri);
        } catch (final Throwable e) {
          module.onUnexpectedException("beforeConstructor threw", e);
        }
      }
    }
  }
}
