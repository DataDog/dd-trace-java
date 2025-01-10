package datadog.trace.instrumentation.java.lang.jdk15;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.StringModule;

@Propagation
@CallSite(
    spi = IastCallSites.class,
    enabled = {"datadog.trace.api.iast.IastEnabledChecks", "isMajorJavaVersionAtLeast", "15"})
public class StringCallSite {
  @CallSite.After("java.lang.String java.lang.String.translateEscapes()")
  public static String afterTranslateEscapes(
      @CallSite.This final String self, @CallSite.Return final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    try {
      if (module != null) {
        module.onStringTranslateEscapes(self, result);
      }
    } catch (final Throwable e) {
      module.onUnexpectedException("afterTranslateEscapes threw", e);
    }
    return result;
  }
}
