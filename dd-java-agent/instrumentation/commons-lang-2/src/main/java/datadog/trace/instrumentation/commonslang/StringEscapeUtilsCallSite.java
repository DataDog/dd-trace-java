package datadog.trace.instrumentation.commonslang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.VulnerabilityMarks;
import datadog.trace.api.iast.propagation.PropagationModule;
import javax.annotation.Nonnull;

@Propagation
@CallSite(spi = IastCallSites.class)
public class StringEscapeUtilsCallSite {

  @CallSite.After(
      "java.lang.String org.apache.commons.lang.StringEscapeUtils.escapeHtml(java.lang.String)")
  public static String afterEscapeHtml(
      @CallSite.Argument(0) @Nonnull final String input, @CallSite.Return final String result) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taintIfInputIsTaintedWithMarks(result, input, VulnerabilityMarks.XSS_MARK);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterEscapeHtml threw", e);
      }
    }
    return result;
  }
}
