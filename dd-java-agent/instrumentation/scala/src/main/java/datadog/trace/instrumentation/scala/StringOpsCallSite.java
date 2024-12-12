package datadog.trace.instrumentation.scala;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.StringModule;
import javax.annotation.Nonnull;
import scala.collection.immutable.StringOps;

@Propagation
@CallSite(
    spi = IastCallSites.class,
    helpers = {
      ScalaJavaConverters.class,
      ScalaJavaConverters.JavaIterable.class,
      ScalaJavaConverters.JavaIterator.class
    })
public class StringOpsCallSite {

  @CallSite.After(
      "java.lang.String scala.collection.immutable.StringOps.format(scala.collection.Seq)")
  public static String afterInterpolation(
      @CallSite.This @Nonnull final StringOps stringOps,
      @CallSite.Argument(0) final scala.collection.Seq<?> params,
      @CallSite.Return @Nonnull final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    if (module != null) {
      try {
        final Object[] args = ScalaJavaConverters.toArray(params);
        module.onStringFormat(stringOps.toString(), args, result);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterInterpolation threw", e);
      }
    }
    return result;
  }

  @CallSite.After(
      "java.lang.String scala.collection.StringOps$.format$extension(java.lang.String, scala.collection.immutable.Seq)")
  public static String afterInterpolation(
      @CallSite.This final Object target, // CSI forces to include the target of the invoke
      @CallSite.Argument(0) @Nonnull final String pattern,
      @CallSite.Argument(1) final scala.collection.immutable.Seq<?> params,
      @CallSite.Return @Nonnull final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    if (module != null) {
      try {
        final Object[] args = ScalaJavaConverters.toArray(params);
        module.onStringFormat(pattern, args, result);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterInterpolation threw", e);
      }
    }
    return result;
  }
}
