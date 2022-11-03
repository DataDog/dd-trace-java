package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import javax.annotation.Nullable;

// TODO deal with the environment
@CallSite(spi = IastAdvice.class)
public class ProcessBuilderCallSite {

  @CallSite.Before("java.lang.Process java.lang.ProcessBuilder.start()")
  public static void beforeStart(@CallSite.This @Nullable final ProcessBuilder self) {
    ProcessBuilderHelperContainer.onProcessBuilderStart(self);
  }
}
