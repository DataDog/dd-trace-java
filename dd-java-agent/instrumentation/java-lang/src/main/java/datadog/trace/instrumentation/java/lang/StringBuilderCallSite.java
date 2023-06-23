package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.StringModule;
import datadog.trace.util.stacktrace.StackUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@Propagation
@CallSite(spi = IastCallSites.class)
public class StringBuilderCallSite {

  @CallSite.After("void java.lang.StringBuilder.<init>(java.lang.String)")
  @CallSite.After("void java.lang.StringBuilder.<init>(java.lang.CharSequence)")
  @CallSite.After("void java.lang.StringBuffer.<init>(java.lang.String)")
  @CallSite.After("void java.lang.StringBuffer.<init>(java.lang.CharSequence)")
  @Nonnull
  public static CharSequence afterInit(
      @CallSite.AllArguments @Nonnull final Object[] params,
      @CallSite.Return @Nonnull final CharSequence result) {
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

  @CallSite.After("java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.String)")
  @CallSite.After("java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.CharSequence)")
  @CallSite.After("java.lang.StringBuffer java.lang.StringBuffer.append(java.lang.String)")
  @CallSite.After("java.lang.StringBuffer java.lang.StringBuffer.append(java.lang.CharSequence)")
  @Nonnull
  public static CharSequence afterAppend(
      @CallSite.This @Nonnull final CharSequence self,
      @CallSite.Argument(0) @Nullable final CharSequence param,
      @CallSite.Return @Nonnull final CharSequence result) {
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

  @CallSite.Around("java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.Object)")
  @CallSite.Around("java.lang.StringBuffer java.lang.StringBuffer.append(java.lang.Object)")
  @Nonnull
  @SuppressFBWarnings(
      "NP_PARAMETER_MUST_BE_NONNULL_BUT_MARKED_AS_NULLABLE") // we do check for null on self
  // parameter
  public static Appendable aroundAppend(
      @CallSite.This @Nullable final Appendable self,
      @CallSite.Argument(0) @Nullable final Object param)
      throws Throwable {
    try {
      if (self == null) {
        throw new NullPointerException();
      }
      final String paramStr = String.valueOf(param);
      final Appendable result = self.append(paramStr);
      final StringModule module = InstrumentationBridge.STRING;
      if (module != null) {
        try {
          module.onStringBuilderAppend((CharSequence) self, paramStr);
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

  @CallSite.After("java.lang.String java.lang.StringBuilder.toString()")
  @CallSite.After("java.lang.String java.lang.StringBuffer.toString()")
  @Nonnull
  public static String afterToString(
      @CallSite.This @Nonnull final CharSequence self,
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
