package datadog.trace.instrumentation.java.net;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.IastAdvice.Propagation;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.model.PropagationTypes;
import datadog.trace.api.iast.propagation.UrlModule;
import javax.annotation.Nullable;

@Propagation(PropagationTypes.URL)
@CallSite(spi = IastAdvice.class)
public class URLDecoderCallSite {

  @CallSite.After("java.lang.String java.net.URLDecoder.decode(java.lang.String)")
  public static String afterDecode(
      @CallSite.Argument @Nullable final String value,
      @CallSite.Return @Nullable final String result) {
    if (value != null && result != null) {
      final UrlModule module = InstrumentationBridge.URL;
      if (module != null) {
        try {
          module.onDecode(value, null, result);
        } catch (final Throwable e) {
          module.onUnexpectedException("afterDecode threw", e);
        }
      }
    }
    return result;
  }

  @CallSite.After("java.lang.String java.net.URLDecoder.decode(java.lang.String, java.lang.String)")
  public static String afterDecode(
      @CallSite.Argument @Nullable final String value,
      @CallSite.Argument @Nullable final String encoding,
      @CallSite.Return @Nullable final String result) {
    if (value != null && result != null) {
      final UrlModule module = InstrumentationBridge.URL;
      if (module != null) {
        try {
          module.onDecode(value, encoding, result);
        } catch (final Throwable e) {
          module.onUnexpectedException("afterDecode threw", e);
        }
      }
    }
    return result;
  }
}
