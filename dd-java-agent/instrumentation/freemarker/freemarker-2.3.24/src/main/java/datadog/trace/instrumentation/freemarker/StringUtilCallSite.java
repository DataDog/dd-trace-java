package datadog.trace.instrumentation.freemarker;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.VulnerabilityMarks;
import datadog.trace.api.iast.propagation.PropagationModule;
import javax.annotation.Nullable;

@Propagation
@CallSite(spi = IastCallSites.class)
public class StringUtilCallSite {

  @CallSite.After(
      "java.lang.String freemarker.template.utility.StringUtil.HTMLEnc(java.lang.String)")
  @CallSite.After(
      "java.lang.String freemarker.template.utility.StringUtil.XMLEnc(java.lang.String)")
  @CallSite.After(
      "java.lang.String freemarker.template.utility.StringUtil.XHTMLEnc(java.lang.String)")
  @CallSite.After(
      "java.lang.String freemarker.template.utility.StringUtil.javaStringEnc(java.lang.String)")
  @CallSite.After(
      "java.lang.String freemarker.template.utility.StringUtil.javaScriptStringEnc(java.lang.String)")
  @CallSite.After(
      "java.lang.String freemarker.template.utility.StringUtil.jsonStringEnc(java.lang.String)")
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
}
