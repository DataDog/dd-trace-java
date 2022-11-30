package datadog.trace.instrumentation.java.security;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import java.security.Provider;

@CallSite(spi = IastAdvice.class)
public class WeakHashInstrumentationCallSite {

  @CallSite.Before(
      "java.security.MessageDigest java.security.MessageDigest.getInstance(java.lang.String)")
  public static void beforeGetInstance(@CallSite.Argument final String algo) {
    InstrumentationBridge.onMessageDigestGetInstance(algo);
  }

  @CallSite.Before(
      "java.security.MessageDigest java.security.MessageDigest.getInstance(java.lang.String, java.lang.String)")
  public static void beforeGetInstance(
      @CallSite.Argument final String algo, @CallSite.Argument final String provider) {
    InstrumentationBridge.onMessageDigestGetInstance(algo);
  }

  @CallSite.Before(
      "java.security.MessageDigest java.security.MessageDigest.getInstance(java.lang.String, java.security.Provider)")
  public static void beforeGetInstance(
      @CallSite.Argument final String algo, @CallSite.Argument final Provider provider) {
    InstrumentationBridge.onMessageDigestGetInstance(algo);
  }
}
