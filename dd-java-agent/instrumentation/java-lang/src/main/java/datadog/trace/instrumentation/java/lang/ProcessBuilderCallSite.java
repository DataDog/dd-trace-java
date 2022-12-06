package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import java.util.List;
import javax.annotation.Nullable;

// TODO deal with the environment
@CallSite(spi = IastAdvice.class)
public class ProcessBuilderCallSite {

  @CallSite.Before("java.lang.Process java.lang.ProcessBuilder.start()")
  public static void beforeStart(@CallSite.This @Nullable final ProcessBuilder self) {
    if (self == null) {
      return;
    }
    // be careful when fetching the environment as it does mutate the instance
    final List<String> cmd = self.command();
    if (cmd != null && cmd.size() > 0) {
      InstrumentationBridge.onProcessBuilderStart(cmd);
    }
  }
}
