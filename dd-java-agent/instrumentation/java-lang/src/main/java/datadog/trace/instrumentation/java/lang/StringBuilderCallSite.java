package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.util.stacktrace.StackUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@CallSite(spi = IastAdvice.class)
public class StringBuilderCallSite {

  @CallSite.AfterArray(
      value = {
        @CallSite.After("void java.lang.StringBuilder.<init>(java.lang.String)"),
        @CallSite.After("void java.lang.StringBuilder.<init>(java.lang.CharSequence)")
      })
  @Nonnull
  public static StringBuilder afterInit(
      @CallSite.This @Nonnull final StringBuilder self,
      @CallSite.Argument @Nullable final CharSequence param) {
    InstrumentationBridge.onStringBuilderInit(self, param);
    return self;
  }

  @CallSite.AfterArray(
      value = {
        @CallSite.After("java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.String)"),
        @CallSite.After(
            "java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.CharSequence)")
      })
  @Nonnull
  public static StringBuilder afterAppend(
      @CallSite.This @Nonnull final StringBuilder self,
      @CallSite.Argument @Nullable final CharSequence param,
      @CallSite.Return @Nonnull final StringBuilder result) {
    InstrumentationBridge.onStringBuilderAppend(self, param);
    return result;
  }

  @CallSite.Around("java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.Object)")
  @Nonnull
  @SuppressFBWarnings(
      "NP_PARAMETER_MUST_BE_NONNULL_BUT_MARKED_AS_NULLABLE") // we do check for null on self
  // parameter
  public static StringBuilder aroundAppend(
      @CallSite.This @Nullable final StringBuilder self,
      @CallSite.Argument @Nullable final Object param)
      throws Throwable {
    try {
      if (self == null) {
        throw new NullPointerException();
      }
      final String paramStr = String.valueOf(param);
      final StringBuilder result = self.append(paramStr);
      InstrumentationBridge.onStringBuilderAppend(result, paramStr);
      return result;
    } catch (final Throwable e) {
      throw StackUtils.filterDatadog(e);
    }
  }

  @CallSite.After("java.lang.String java.lang.StringBuilder.toString()")
  @Nonnull
  public static String afterToString(
      @CallSite.This @Nonnull final StringBuilder self,
      @CallSite.Return @Nonnull final String result) {
    InstrumentationBridge.onStringBuilderToString(self, result);
    return result;
  }
}
