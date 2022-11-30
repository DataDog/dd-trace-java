package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import java.io.File;
import java.net.URI;
import javax.annotation.Nullable;

@CallSite(spi = IastAdvice.class)
public class FileCallSite {

  @CallSite.Before("void java.io.File.<init>(java.lang.String)")
  public static void beforeConstructor(@CallSite.Argument @Nullable final String path) {
    if (path != null) { // new File(null) throws NPE
      InstrumentationBridge.onPathTraversal(path);
    }
  }

  @CallSite.Before("void java.io.File.<init>(java.lang.String, java.lang.String)")
  public static void beforeConstructor(
      @CallSite.Argument @Nullable final String parent,
      @CallSite.Argument @Nullable final String child) {
    if (child != null) { // new File("abc", null) throws NPE
      InstrumentationBridge.onPathTraversal(parent, child);
    }
  }

  @CallSite.Before("void java.io.File.<init>(java.io.File, java.lang.String)")
  public static void beforeConstructor(
      @CallSite.Argument @Nullable final File parent,
      @CallSite.Argument @Nullable final String child) {
    if (child != null) { // new File(parent, null) throws NPE
      InstrumentationBridge.onPathTraversal(parent, child);
    }
  }

  @CallSite.Before("void java.io.File.<init>(java.net.URI)")
  public static void beforeConstructor(@CallSite.Argument @Nullable final URI uri) {
    if (uri != null) {
      InstrumentationBridge.onPathTraversal(uri);
    }
  }
}
