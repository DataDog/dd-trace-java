package datadog.trace.instrumentation.java.security;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import javax.crypto.Cipher;

@CallSite(spi = IastAdvice.class)
public class WeakCipherInstrumentationCallSite {

  @CallSite.After("javax.crypto.Cipher javax.crypto.Cipher.getInstance(java.lang.String)")
  public static Cipher afterGetInstance(
      @CallSite.Argument final String algo, @CallSite.Return final Cipher retValue) {
    InstrumentationBridge.onCipherGetInstance(algo);
    return retValue;
  }
}
