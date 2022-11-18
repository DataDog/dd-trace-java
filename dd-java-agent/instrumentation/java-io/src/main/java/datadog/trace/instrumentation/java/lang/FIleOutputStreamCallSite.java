package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import javax.annotation.Nullable;

@CallSite(spi = IastAdvice.class)
public class FIleOutputStreamCallSite {

  @CallSite.Before("void java.io.FileOutputStream.<init>(java.lang.String)")
  public static void beforeConstructor(@CallSite.Argument @Nullable final String path) {
    if (path != null) {
      InstrumentationBridge.onPathTraversal(path);
    }
  }
}
