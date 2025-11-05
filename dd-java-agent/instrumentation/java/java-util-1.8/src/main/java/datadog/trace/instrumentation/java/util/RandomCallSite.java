package datadog.trace.instrumentation.java.util;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.WeakRandomnessModule;
import java.util.Random;

@Sink(VulnerabilityTypes.WEAK_RANDOMNESS)
@CallSite(spi = IastCallSites.class)
public class RandomCallSite {

  @CallSite.Before("boolean java.util.Random.nextBoolean()")
  @CallSite.Before("int java.util.Random.nextInt()")
  @CallSite.Before("int java.util.Random.nextInt(int)")
  @CallSite.Before("long java.util.Random.nextLong()")
  @CallSite.Before("float java.util.Random.nextFloat()")
  @CallSite.Before("double java.util.Random.nextDouble()")
  @CallSite.Before("double java.util.Random.nextGaussian()")
  @CallSite.Before("void java.util.Random.nextBytes(byte[])")
  @CallSite.Before("java.util.stream.IntStream java.util.Random.ints()")
  @CallSite.Before("java.util.stream.IntStream java.util.Random.ints(int, int)")
  @CallSite.Before("java.util.stream.IntStream java.util.Random.ints(long)")
  @CallSite.Before("java.util.stream.IntStream java.util.Random.ints(long, int, int)")
  @CallSite.Before("java.util.stream.DoubleStream java.util.Random.doubles()")
  @CallSite.Before("java.util.stream.DoubleStream java.util.Random.doubles(double, double)")
  @CallSite.Before("java.util.stream.DoubleStream java.util.Random.doubles(long)")
  @CallSite.Before("java.util.stream.DoubleStream java.util.Random.doubles(long, double, double)")
  @CallSite.Before("java.util.stream.LongStream java.util.Random.longs()")
  @CallSite.Before("java.util.stream.LongStream java.util.Random.longs(long)")
  @CallSite.Before("java.util.stream.LongStream java.util.Random.longs(long, long)")
  @CallSite.Before("java.util.stream.LongStream java.util.Random.longs(long, long, long)")
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
