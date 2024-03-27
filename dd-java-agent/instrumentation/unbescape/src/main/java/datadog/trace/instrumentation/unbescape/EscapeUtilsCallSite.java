package datadog.trace.instrumentation.unbescape;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.VulnerabilityMarks;
import datadog.trace.api.iast.propagation.PropagationModule;
import javax.annotation.Nullable;

@Propagation
@CallSite(spi = IastCallSites.class)
public class EscapeUtilsCallSite {

  @CallSite.After("java.lang.String org.unbescape.html.HtmlEscape.escapeHtml4Xml(java.lang.String)")
  @CallSite.After("java.lang.String org.unbescape.html.HtmlEscape.escapeHtml4(java.lang.String)")
  @CallSite.After("java.lang.String org.unbescape.html.HtmlEscape.escapeHtml5Xml(java.lang.String)")
  @CallSite.After("java.lang.String org.unbescape.html.HtmlEscape.escapeHtml5(java.lang.String)")
  @CallSite.After(
      "java.lang.String org.unbescape.javascript.JavaScriptEscape.escapeJavaScript(java.lang.String)")
  public static String afterEscape(
      @CallSite.Argument(0) @Nullable final String input, @CallSite.Return final String result) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (result != null && module != null) {
      try {
        final IastContext ctx = IastContext.Provider.get();
        if (ctx != null) {
          module.taintIfTainted(ctx, result, input, false, VulnerabilityMarks.XSS_MARK);
        }
      } catch (final Throwable e) {
        module.onUnexpectedException("afterEscape threw", e);
      }
    }
    return result;
  }
}
