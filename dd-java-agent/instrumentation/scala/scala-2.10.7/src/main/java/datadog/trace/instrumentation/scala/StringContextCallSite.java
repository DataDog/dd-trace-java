package datadog.trace.instrumentation.scala;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.StringModule;
import javax.annotation.Nonnull;
import scala.StringContext;
import scala.collection.Seq;

@Propagation
@CallSite(
    spi = IastCallSites.class,
    helpers = {
      ScalaJavaConverters.class,
      ScalaJavaConverters.JavaIterable.class,
      ScalaJavaConverters.JavaIterator.class
    })
public class StringContextCallSite {

  @CallSite.After("java.lang.String scala.StringContext.s(scala.collection.Seq)")
  @CallSite.After("java.lang.String scala.StringContext.raw(scala.collection.Seq)")
  @Nonnull
  public static String afterInterpolation(
      @CallSite.This @Nonnull final StringContext context,
      @CallSite.Argument final Seq<?> params,
      @CallSite.Return @Nonnull final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    if (module != null) {
      try {
        final Iterable<String> literals = ScalaJavaConverters.toIterable(context.parts());
        final Object[] args = ScalaJavaConverters.toArray(params);
        module.onStringFormat(literals, args, result);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterInterpolation threw", e);
      }
    }
    return result;
  }
}
