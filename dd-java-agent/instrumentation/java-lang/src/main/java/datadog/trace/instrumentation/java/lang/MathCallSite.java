package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.WeakRandomnessModule;

@Sink(VulnerabilityTypes.WEAK_RANDOMNESS)
@CallSite(spi = IastCallSites.class)
public class MathCallSite {

  @CallSite.Before("double java.lang.Math.random()")
  public static void before() {
    final WeakRandomnessModule module = InstrumentationBridge.WEAK_RANDOMNESS;
    if (module != null) {
      try {
        module.onWeakRandom(Math.class);
      } catch (Throwable e) {
        module.onUnexpectedException("random threw", e);
      }
    }
  }
}
