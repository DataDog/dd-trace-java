package datadog.trace.instrumentation.java.security;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import java.security.MessageDigest;

@CallSite(spi = IastAdvice.class)
public class WeakHashInstrumentationCallSite {

  /*
  @CallSite.Before(
      "java.security.MessageDigest java.security.MessageDigest.getInstance(java.lang.String)")
  public static void beforeGetInstance(@CallSite.Argument final String algo) {
    InstrumentationBridge.onMessageDigestGetInstance(algo);
  }
   */

  @CallSite.After(
      "java.security.MessageDigest java.security.MessageDigest.getInstance(java.lang.String)")
  public static MessageDigest afterGetInstance(
      @CallSite.Argument final String algo, @CallSite.Return final MessageDigest retValue) {
    InstrumentationBridge.onMessageDigestGetInstance(algo);
    return retValue;
  }
}
