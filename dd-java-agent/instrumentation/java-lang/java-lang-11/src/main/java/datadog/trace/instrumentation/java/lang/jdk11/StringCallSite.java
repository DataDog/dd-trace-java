package datadog.trace.instrumentation.java.lang.jdk11;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.StringModule;

@Propagation
@CallSite(
    spi = IastCallSites.class,
    enabled = {"datadog.trace.api.iast.IastEnabledChecks", "isMajorJavaVersionAtLeast", "11"})
public class StringCallSite {
  @CallSite.After("java.lang.String java.lang.String.repeat(int)")
  public static String afterRepeat(
      @CallSite.This final String self,
      @CallSite.Argument final int count,
      @CallSite.Return final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    try {
      if (module != null) {
        module.onStringRepeat(self, count, result);
      }
    } catch (final Throwable e) {
      module.onUnexpectedException("afterRepeat threw", e);
    }
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.strip()")
  @CallSite.After("java.lang.String java.lang.String.stripLeading()")
  public static String afterStrip(
      @CallSite.This final String self, @CallSite.Return final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    try {
      if (module != null) {
        module.onStringStrip(self, result, false);
      }
    } catch (final Throwable e) {
      module.onUnexpectedException("afterStrip threw", e);
    }
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.stripTrailing()")
  public static String afterStripTrailing(
      @CallSite.This final String self, @CallSite.Return final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    try {
      if (module != null) {
        module.onStringStrip(self, result, true);
      }
    } catch (final Throwable e) {
      module.onUnexpectedException("afterStripTrailing threw", e);
    }
    return result;
  }
}
