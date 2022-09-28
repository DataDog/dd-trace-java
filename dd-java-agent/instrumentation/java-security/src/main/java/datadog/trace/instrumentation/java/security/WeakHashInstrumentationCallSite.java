package datadog.trace.instrumentation.java.security;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;

@CallSite(spi = IastAdvice.class)
public class WeakHashInstrumentationCallSite {

  @CallSite.Before(
      "java.security.MessageDigest java.security.MessageDigest.getInstance(java.lang.String)")
  public static void beforeGetInstance(@CallSite.Argument final String algo) {
    InstrumentationBridge.onMessageDigestGetInstance(algo);
  }
}
