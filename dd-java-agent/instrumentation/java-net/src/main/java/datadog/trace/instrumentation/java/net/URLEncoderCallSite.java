package datadog.trace.instrumentation.java.net;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.VulnerabilityMarks;
import datadog.trace.api.iast.propagation.PropagationModule;
import javax.annotation.Nullable;

@Propagation
@CallSite(spi = IastCallSites.class)
public class URLEncoderCallSite {

  @CallSite.After("java.lang.String java.net.URLEncoder.encode(java.lang.String)")
  public static String afterEncode(
      @CallSite.Argument @Nullable final String value,
      @CallSite.Return @Nullable final String result) {
    return encode(result, value);
  }

  @CallSite.After("java.lang.String java.net.URLEncoder.encode(java.lang.String, java.lang.String)")
  public static String afterEncode(
      @CallSite.Argument @Nullable final String value,
      @CallSite.Argument @Nullable final String encoding,
      @CallSite.Return @Nullable final String result) {
    return encode(result, value);
  }

  private static String encode(final String result, final String value) {
    if (value != null && result != null) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        try {
          module.taintStringIfTainted(result, value, false, VulnerabilityMarks.XSS_MARK);
        } catch (final Throwable e) {
          module.onUnexpectedException("afterEncode threw", e);
        }
      }
    }
    return result;
  }
}
