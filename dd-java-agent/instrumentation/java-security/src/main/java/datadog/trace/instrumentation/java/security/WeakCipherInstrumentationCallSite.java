package datadog.trace.instrumentation.java.security;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.WeakCipherModule;
import java.security.Provider;

@Sink(VulnerabilityTypes.WEAK_CIPHER)
@CallSite(spi = IastCallSites.class)
public class WeakCipherInstrumentationCallSite {

  @CallSite.Before("javax.crypto.Cipher javax.crypto.Cipher.getInstance(java.lang.String)")
  public static void beforeGetInstance(@CallSite.Argument final String algo) {
    onCipherAlgorithm(algo);
  }

  @CallSite.Before(
      "javax.crypto.Cipher javax.crypto.Cipher.getInstance(java.lang.String, java.lang.String)")
  public static void beforeGetInstance(
      @CallSite.Argument final String algo, @CallSite.Argument final String provider) {
    onCipherAlgorithm(algo);
  }

  @CallSite.Before(
      "javax.crypto.Cipher javax.crypto.Cipher.getInstance(java.lang.String, java.security.Provider)")
  public static void beforeGetInstance(
      @CallSite.Argument final String algo, @CallSite.Argument final Provider provider) {
    onCipherAlgorithm(algo);
  }

  private static void onCipherAlgorithm(final String algo) {
    if (algo != null) {
      final WeakCipherModule module = InstrumentationBridge.WEAK_CIPHER;
      if (module != null) {
        try {
          module.onCipherAlgorithm(algo);
        } catch (final Throwable e) {
          module.onUnexpectedException("beforeGetInstance threw", e);
        }
      }
    }
  }
}
