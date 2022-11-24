package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import javax.annotation.Nullable;

@CallSite(spi = IastAdvice.class)
public class FileInputStreamCallSite {

  @CallSite.Before("void java.io.FileInputStream.<init>(java.lang.String)")
  public static void beforeConstructor(@CallSite.Argument @Nullable final String path) {
    if (path != null) {
      InstrumentationBridge.onPathTraversal(path);
    }
  }
}
