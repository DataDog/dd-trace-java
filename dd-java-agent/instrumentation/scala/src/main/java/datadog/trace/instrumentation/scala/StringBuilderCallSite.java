package datadog.trace.instrumentation.scala;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.StringModule;
import datadog.trace.util.stacktrace.StackUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import scala.collection.mutable.StringBuilder;

@Propagation
@CallSite(spi = IastCallSites.class)
public class StringBuilderCallSite {

  @CallSite.After("void scala.collection.mutable.StringBuilder.<init>(java.lang.String)")
  @Nonnull
  public static StringBuilder afterInit(
      @CallSite.AllArguments @Nonnull final Object[] params,
      @CallSite.Return @Nonnull final StringBuilder result) {
    final StringModule module = InstrumentationBridge.STRING;
    if (module != null) {
      try {

        module.onStringBuilderInit(result, (CharSequence) params[0]);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterInit threw", e);
      }
    }
    return result;
  }

  @CallSite.After("void scala.collection.mutable.StringBuilder.<init>(int, java.lang.String)")
  @Nonnull
  public static StringBuilder afterInitWithCapacity(
      @CallSite.AllArguments @Nonnull final Object[] params,
      @CallSite.Return @Nonnull final StringBuilder result) {
    final StringModule module = InstrumentationBridge.STRING;
    if (module != null) {
      try {

        module.onStringBuilderInit(result, (CharSequence) params[1]);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterInitWithCapacity threw", e);
      }
    }
    return result;
  }

  @CallSite.After(
      "scala.collection.mutable.StringBuilder scala.collection.mutable.StringBuilder.append(java.lang.String)")
  @CallSite.After(
      "scala.collection.mutable.StringBuilder scala.collection.mutable.StringBuilder.append(scala.collection.mutable.StringBuilder)")
  @Nonnull
  public static StringBuilder afterAppend(
      @CallSite.This @Nonnull final StringBuilder self,
      @CallSite.Argument(0) @Nullable final CharSequence param,
      @CallSite.Return @Nonnull final StringBuilder result) {
    final StringModule module = InstrumentationBridge.STRING;
    if (module != null) {
      try {
        module.onStringBuilderAppend(self, param);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterAppend threw", e);
      }
    }
    return result;
  }

  @CallSite.Around(
      "scala.collection.mutable.StringBuilder scala.collection.mutable.StringBuilder.append(java.lang.Object)")
  @Nonnull
  @SuppressFBWarnings(
      "NP_PARAMETER_MUST_BE_NONNULL_BUT_MARKED_AS_NULLABLE") // we do check for null on self
  // parameter
  public static StringBuilder aroundAppend(
      @CallSite.This @Nullable final StringBuilder self,
      @CallSite.Argument(0) @Nullable final Object param)
      throws Throwable {
    try {
      if (self == null) {
        throw new NullPointerException();
      }
      final String paramStr = String.valueOf(param);
      final StringBuilder result = self.append(paramStr);
      final StringModule module = InstrumentationBridge.STRING;
      if (module != null) {
        try {
          module.onStringBuilderAppend(self, paramStr);
        } catch (final Throwable e) {
          module.onUnexpectedException("aroundAppend threw", e);
        }
      }
      return result;
    } catch (final Throwable e) {
      final String clazz = StringBuilderCallSite.class.getName();
      throw StackUtils.filterUntil(
          e, s -> s.getClassName().equals(clazz) && s.getMethodName().equals("aroundAppend"));
    }
  }

  @CallSite.After("java.lang.String scala.collection.mutable.StringBuilder.toString()")
  @Nonnull
  public static String afterToString(
      @CallSite.This @Nonnull final StringBuilder self,
      @CallSite.Return @Nonnull final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    if (module != null) {
      try {
        module.onStringBuilderToString(self, result);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterToString threw", e);
      }
    }
    return result;
  }
}
