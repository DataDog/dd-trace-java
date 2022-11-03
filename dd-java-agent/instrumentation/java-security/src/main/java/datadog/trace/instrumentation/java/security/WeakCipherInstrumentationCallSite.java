package datadog.trace.instrumentation.java.security;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;

@CallSite(spi = IastAdvice.class)
public class WeakCipherInstrumentationCallSite {

  @CallSite.Before("javax.crypto.Cipher javax.crypto.Cipher.getInstance(java.lang.String)")
  public static void beforeGetInstance(@CallSite.Argument final String algo) {
    WeakCipherInstrumentationHelperContainer.onCipherAlgorithm(algo);
  }
}
