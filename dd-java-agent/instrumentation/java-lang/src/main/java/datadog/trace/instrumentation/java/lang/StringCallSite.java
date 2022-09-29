package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
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
    InstrumentationBridge.onStringConcat(self, param, result);
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.substring(int)")
  public static String afterSubstring(
      @CallSite.This final String self,
      @CallSite.Argument final int beginIndex,
      @CallSite.Return final String result) {
    InstrumentationBridge.onStringSubSequence(
        self, beginIndex, self != null ? self.length() : 0, result);
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.substring(int, int)")
  public static String afterSubstring(
      @CallSite.This final String self,
      @CallSite.Argument final int beginIndex,
      @CallSite.Argument final int endIndex,
      @CallSite.Return final String result) {
    InstrumentationBridge.onStringSubSequence(self, beginIndex, endIndex, result);
    return result;
  }

  @CallSite.After("java.lang.CharSequence java.lang.String.subSequence(int, int)")
  public static CharSequence afterSubSequence(
      @CallSite.This final String self,
      @CallSite.Argument final int beginIndex,
      @CallSite.Argument final int endIndex,
      @CallSite.Return final CharSequence result) {
    InstrumentationBridge.onStringSubSequence(self, beginIndex, endIndex, result);
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.toUpperCase()")
  public static String afterToUppercase(
      @CallSite.This final String self, @CallSite.Return final String result) {
    InstrumentationBridge.onStringToUppercase(self, result);
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.toUpperCase(java.util.Locale)")
  public static String afterToUppercase(
      @CallSite.This final String self,
      @CallSite.Argument final Locale locale,
      @CallSite.Return final String result) {
    InstrumentationBridge.onStringToUppercase(self, result);
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.toLowerCase()")
  public static String afterToLowerCase(
      @CallSite.This final String self, @CallSite.Return final String result) {
    InstrumentationBridge.onStringToLowercase(self, result);
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.toLowerCase(java.util.Locale)")
  public static String afterToLowercase(
      @CallSite.This final String self,
      @CallSite.Argument final Locale locale,
      @CallSite.Return final String result) {
    InstrumentationBridge.onStringToLowercase(self, result);
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.trim()")
  public static String afterTrim(
      @CallSite.This final String self, @CallSite.Return final String result) {
    InstrumentationBridge.onStringTrim(self, result);
    return result;
  }
}
