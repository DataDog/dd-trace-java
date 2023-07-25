package datadog.trace.instrumentation.owasp.esapi;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.VulnerabilityMarks;
import datadog.trace.api.iast.propagation.PropagationModule;
import javax.annotation.Nonnull;
import org.owasp.esapi.Encoder;

@Propagation
@CallSite(spi = IastCallSites.class)
public class EncoderCallSite {

  @CallSite.After("java.lang.String org.owasp.esapi.Encoder.encodeForHTML(java.lang.String)")
  public static String afterEncodeForHTML(
      @CallSite.This final Encoder encoder,
      @CallSite.Argument(0) @Nonnull final String input,
      @CallSite.Return final String result) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taintIfInputIsTaintedWithMarks(result, input, VulnerabilityMarks.XSS_MARK);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterEncodeForHTML threw", e);
      }
    }
    return result;
  }
}
