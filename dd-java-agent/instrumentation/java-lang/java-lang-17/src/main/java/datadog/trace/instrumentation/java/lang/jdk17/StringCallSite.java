package datadog.trace.instrumentation.java.lang.jdk17;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.StringModule;

@Propagation
@CallSite(
    spi = IastCallSites.class,
    enabled = {"datadog.trace.api.iast.IastEnabledChecks", "isMajorJavaVersionAtLeast", "17"})
public class StringCallSite {
  @CallSite.After("java.lang.String java.lang.String.indent(int)")
  public static String afterIndent(
      @CallSite.This final String self,
      @CallSite.Argument final int indentation,
      @CallSite.Return final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    try {
      if (module != null) {
        module.onIndent(self, indentation, result);
      }
    } catch (final Throwable e) {
      module.onUnexpectedException("afterIndent threw", e);
    }
    return result;
  }
}
