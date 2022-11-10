package datadog.trace.instrumentation.java.security;

import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_WEAK_HASH_ENABLED;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.config.IastConfig;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.WeakHashModule;
import java.security.Provider;

@CallSite(
    spi = IastAdvice.class,
    featureFlag = IastConfig.IAST_WEAK_HASH_ENABLED,
    featureFlagDefault = DEFAULT_IAST_WEAK_HASH_ENABLED)
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
