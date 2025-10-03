package datadog.trace.instrumentation.java.util.concurrent;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.WeakRandomnessModule;
import java.util.concurrent.ThreadLocalRandom;

@Sink(VulnerabilityTypes.WEAK_RANDOMNESS)
@CallSite(spi = IastCallSites.class)
public class ThreadLocalRandomCallSite {

  @CallSite.Before("boolean java.util.concurrent.ThreadLocalRandom.nextBoolean()")
  @CallSite.Before("int java.util.concurrent.ThreadLocalRandom.nextInt()")
  @CallSite.Before("int java.util.concurrent.ThreadLocalRandom.nextInt(int)")
  @CallSite.Before("long java.util.concurrent.ThreadLocalRandom.nextLong()")
  @CallSite.Before("float java.util.concurrent.ThreadLocalRandom.nextFloat()")
  @CallSite.Before("double java.util.concurrent.ThreadLocalRandom.nextDouble()")
  @CallSite.Before("double java.util.concurrent.ThreadLocalRandom.nextGaussian()")
  @CallSite.Before("void java.util.concurrent.ThreadLocalRandom.nextBytes(byte[])")
  @CallSite.Before("java.util.stream.IntStream java.util.concurrent.ThreadLocalRandom.ints()")
  @CallSite.Before(
      "java.util.stream.IntStream java.util.concurrent.ThreadLocalRandom.ints(int, int)")
  @CallSite.Before("java.util.stream.IntStream java.util.concurrent.ThreadLocalRandom.ints(long)")
  @CallSite.Before(
      "java.util.stream.IntStream java.util.concurrent.ThreadLocalRandom.ints(long, int, int)")
  @CallSite.Before("java.util.stream.DoubleStream java.util.concurrent.ThreadLocalRandom.doubles()")
  @CallSite.Before(
      "java.util.stream.DoubleStream java.util.concurrent.ThreadLocalRandom.doubles(double, double)")
  @CallSite.Before(
      "java.util.stream.DoubleStream java.util.concurrent.ThreadLocalRandom.doubles(long)")
  @CallSite.Before(
      "java.util.stream.DoubleStream java.util.concurrent.ThreadLocalRandom.doubles(long, double, double)")
  @CallSite.Before("java.util.stream.LongStream java.util.concurrent.ThreadLocalRandom.longs()")
  @CallSite.Before("java.util.stream.LongStream java.util.concurrent.ThreadLocalRandom.longs(long)")
  @CallSite.Before(
      "java.util.stream.LongStream java.util.concurrent.ThreadLocalRandom.longs(long, long)")
  @CallSite.Before(
      "java.util.stream.LongStream java.util.concurrent.ThreadLocalRandom.longs(long, long, long)")
  public static void before(@CallSite.This final ThreadLocalRandom random) {
    final WeakRandomnessModule module = InstrumentationBridge.WEAK_RANDOMNESS;
    if (module != null && random != null) {
      try {
        module.onWeakRandom(random.getClass());
      } catch (final Throwable e) {
        module.onUnexpectedException("thread local random threw", e);
      }
    }
  }
}
