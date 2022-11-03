package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
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
    StringBuilderHelperContainer.onStringBuilderInit(self, param);
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
    StringBuilderHelperContainer.onStringBuilderAppend(self, param);
    return result;
  }

  @CallSite.Around("java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.Object)")
  @Nonnull
  public static StringBuilder aroundAppend(
      @CallSite.This @Nullable final StringBuilder self,
      @CallSite.Argument @Nullable final Object param) {
    return StringBuilderHelperContainer.onStringBuilderAppendObject(self, param);
  }

  @CallSite.After("java.lang.String java.lang.StringBuilder.toString()")
  @Nonnull
  public static String afterToString(
      @CallSite.This @Nonnull final StringBuilder self,
      @CallSite.Return @Nonnull final String result) {
    StringBuilderHelperContainer.onStringBuilderToString(self, result);
    return result;
  }
}
