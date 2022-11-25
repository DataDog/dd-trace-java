package datadog.trace.instrumentation.java.net;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import javax.annotation.Nullable;

@CallSite(spi = IastAdvice.class)
public class URLDecoderCallSite {

  @CallSite.After("java.lang.String java.net.URLDecoder.decode(java.lang.String)")
  public static String afterDecode(
      @CallSite.Argument @Nullable final String value,
      @CallSite.Return @Nullable final String result) {
    if (value != null && result != null) {
      InstrumentationBridge.onURLDecoderDecode(value, null, result);
    }
    return result;
  }

  @CallSite.After("java.lang.String java.net.URLDecoder.decode(java.lang.String, java.lang.String)")
  public static String afterDecode(
      @CallSite.Argument @Nullable final String value,
      @CallSite.Argument @Nullable final String encoding,
      @CallSite.Return @Nullable final String result) {
    if (value != null && result != null) {
      InstrumentationBridge.onURLDecoderDecode(value, encoding, result);
    }
    return result;
  }
}
