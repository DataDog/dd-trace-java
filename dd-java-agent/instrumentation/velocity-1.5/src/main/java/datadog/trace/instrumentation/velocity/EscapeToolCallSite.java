package datadog.trace.instrumentation.velocity;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.VulnerabilityMarks;
import datadog.trace.api.iast.propagation.PropagationModule;
import javax.annotation.Nullable;
import org.apache.velocity.tools.generic.EscapeTool;

@Propagation
@CallSite(spi = IastCallSites.class)
public class EscapeToolCallSite {

  @CallSite.After(
      "java.lang.String org.apache.velocity.tools.generic.EscapeTool.html(java.lang.Object)")
  @CallSite.After(
      "java.lang.String org.apache.velocity.tools.generic.EscapeTool.javascript(java.lang.Object)")
  @CallSite.After(
      "java.lang.String org.apache.velocity.tools.generic.EscapeTool.url(java.lang.Object)")
  @CallSite.After(
      "java.lang.String org.apache.velocity.tools.generic.EscapeTool.xml(java.lang.Object)")
  public static String afterEscape(
      @CallSite.This final EscapeTool self,
      @CallSite.Argument(0) @Nullable final Object input,
      @CallSite.Return final String result) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taintStringIfTainted(result, input, false, VulnerabilityMarks.XSS_MARK);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterEscape threw", e);
      }
    }
    return result;
  }

  @CallSite.After(
      "java.lang.String org.apache.velocity.tools.generic.EscapeTool.sql(java.lang.Object)")
  public static String afterEscapeSQL(
      @CallSite.This final EscapeTool self,
      @CallSite.Argument(0) @Nullable final Object input,
      @CallSite.Return final String result) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taintStringIfTainted(result, input, false, VulnerabilityMarks.SQL_INJECTION_MARK);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterEscapeSQL threw", e);
      }
    }
    return result;
  }
}
