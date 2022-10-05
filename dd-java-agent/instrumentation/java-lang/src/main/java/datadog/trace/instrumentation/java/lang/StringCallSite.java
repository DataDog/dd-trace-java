package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;

@CallSite(spi = IastAdvice.class)
public class StringCallSite {

  @CallSite.After("java.lang.String java.lang.String.concat(java.lang.String)")
  public static String afterConcat(
      @CallSite.This final String self,
      @CallSite.Argument final String param,
      @CallSite.Return final String result) {
    InstrumentationBridge.onStringConcat(self, param, result);
    return result;
  }
}
