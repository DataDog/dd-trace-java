package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@CallSite(spi = IastAdvice.class)
public class StringCallSite {

  @CallSite.After("java.lang.String java.lang.String.concat(java.lang.String)")
  @Nonnull
  public static String afterConcat(
      @CallSite.This @Nonnull final String self,
      @CallSite.Argument @Nullable final String param,
      @CallSite.Return @Nonnull final String result) {
    StringHelperContainer.onStringConcat(self, param, result);
    return result;
  }

  @CallSite.AfterArray(
      value = {
        @CallSite.After("void java.lang.String.<init>(java.lang.String)"),
        @CallSite.After("void java.lang.String.<init>(java.lang.StringBuffer)"),
        @CallSite.After("void java.lang.String.<init>(java.lang.StringBuilder)"),
      })
  public static String afterConstructor(
      @CallSite.This final String self, @CallSite.Argument final CharSequence param) {
    StringHelperContainer.onStringConstructor(param, self);
    return self;
  }

  @CallSite.Around("java.lang.String java.lang.String.format(java.lang.String,java.lang.Object[])")
  public static String format(@CallSite.Argument String fmt, @CallSite.Argument Object[] args) {
    return StringHelperContainer.onStringFormat(null, fmt, args);
  }

  @CallSite.Around(
      "java.lang.String java.lang.String.format(java.util.Locale,java.lang.String,java.lang.Object[])")
  public static String format(
      @CallSite.Argument Locale l,
      @CallSite.Argument String fmt,
      @CallSite.Argument Object[] args) {
    return StringHelperContainer.onStringFormat(l, fmt, args);
  }
}
