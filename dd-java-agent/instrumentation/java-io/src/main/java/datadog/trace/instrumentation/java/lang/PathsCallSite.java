package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import java.net.URI;
import javax.annotation.Nullable;

@CallSite(spi = IastAdvice.class)
public class PathsCallSite {

  @CallSite.Before(
      "java.nio.file.Path java.nio.file.Paths.get(java.lang.String, java.lang.String[])")
  public static void beforeGet(
      @CallSite.Argument @Nullable final String first,
      @CallSite.Argument @Nullable final String[] more) {
    if (first != null && more != null) { // both parameters should be not null
      InstrumentationBridge.onPathTraversal(first, more);
    }
  }

  @CallSite.Before("java.nio.file.Path java.nio.file.Paths.get(java.net.URI)")
  public static void beforeGet(@CallSite.Argument @Nullable final URI uri) {
    if (uri != null) {
      InstrumentationBridge.onPathTraversal(uri);
    }
  }
}
