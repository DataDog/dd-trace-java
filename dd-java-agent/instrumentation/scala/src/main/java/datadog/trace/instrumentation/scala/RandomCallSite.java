package datadog.trace.instrumentation.scala;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.WeakRandomnessModule;
import scala.util.Random;

@Sink(VulnerabilityTypes.WEAK_RANDOMNESS)
@CallSite(spi = IastCallSites.class)
public class RandomCallSite {

  @CallSite.Before("boolean scala.util.Random.nextBoolean()")
  @CallSite.Before("int scala.util.Random.nextInt()")
  @CallSite.Before("int scala.util.Random.nextInt(int)")
  @CallSite.Before("long scala.util.Random.nextLong()")
  @CallSite.Before("float scala.util.Random.nextFloat()")
  @CallSite.Before("double scala.util.Random.nextDouble()")
  @CallSite.Before("double scala.util.Random.nextGaussian()")
  @CallSite.Before("void scala.util.Random.nextBytes(byte[])")
  @CallSite.Before("java.lang.String scala.util.Random.nextString(int)")
  @CallSite.Before("char scala.util.Random.nextPrintableChar()")
  public static void before(@CallSite.This final Random random) {
    final WeakRandomnessModule module = InstrumentationBridge.WEAK_RANDOMNESS;
    if (module != null && random != null) {
      try {
        module.onWeakRandom(random.getClass());
      } catch (final Throwable e) {
        module.onUnexpectedException("random threw", e);
      }
    }
  }
}
