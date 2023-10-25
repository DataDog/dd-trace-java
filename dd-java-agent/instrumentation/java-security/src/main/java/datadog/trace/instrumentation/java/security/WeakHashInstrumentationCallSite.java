package datadog.trace.instrumentation.java.security;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.WeakHashModule;
import java.security.Provider;

@Sink(VulnerabilityTypes.WEAK_HASH)
@CallSite(spi = IastCallSites.class)
public class WeakHashInstrumentationCallSite {

  @CallSite.Before(
      "java.security.MessageDigest java.security.MessageDigest.getInstance(java.lang.String)")
  public static void beforeGetInstance(@CallSite.Argument final String algo) {
    onHashingAlgorithm(algo);
  }

  @CallSite.Before(
      "java.security.MessageDigest java.security.MessageDigest.getInstance(java.lang.String, java.lang.String)")
  public static void beforeGetInstance(
      @CallSite.Argument final String algo, @CallSite.Argument final String provider) {
    onHashingAlgorithm(algo);
  }

  @CallSite.Before(
      "java.security.MessageDigest java.security.MessageDigest.getInstance(java.lang.String, java.security.Provider)")
  public static void beforeGetInstance(
      @CallSite.Argument final String algo, @CallSite.Argument final Provider provider) {
    onHashingAlgorithm(algo);
  }

  private static void onHashingAlgorithm(@CallSite.Argument final String algo) {
    if (algo != null) {
      final WeakHashModule module = InstrumentationBridge.WEAK_HASH;
      if (module != null) {
        try {
          module.onHashingAlgorithm(algo);
        } catch (final Throwable e) {
          module.onUnexpectedException("beforeGetInstance threw", e);
        }
      }
    }
  }
}
