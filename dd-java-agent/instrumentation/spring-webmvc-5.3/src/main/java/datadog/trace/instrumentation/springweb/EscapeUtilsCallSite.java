package datadog.trace.instrumentation.springweb;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.VulnerabilityMarks;
import datadog.trace.api.iast.propagation.PropagationModule;
import javax.annotation.Nullable;

@Propagation
@CallSite(spi = IastCallSites.class)
public class EscapeUtilsCallSite {

  @CallSite.After(
      "java.lang.String org.springframework.web.util.HtmlUtils.htmlEscape(java.lang.String)")
  @CallSite.After(
      "java.lang.String org.springframework.web.util.JavaScriptUtils.javaScriptEscape(java.lang.String)")
  public static String afterEscape(
      @CallSite.Argument(0) @Nullable final String input, @CallSite.Return final String result) {
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
      "java.lang.String org.springframework.web.util.HtmlUtils.htmlEscape(java.lang.String, java.lang.String)")
  public static String afterHtmlEscape2(
      @CallSite.Argument(0) @Nullable final String input,
      @CallSite.Argument(1) @Nullable final String encoding,
      @CallSite.Return final String result) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taintStringIfTainted(result, input, false, VulnerabilityMarks.XSS_MARK);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterHtmlEscape2 threw", e);
      }
    }
    return result;
  }
}
