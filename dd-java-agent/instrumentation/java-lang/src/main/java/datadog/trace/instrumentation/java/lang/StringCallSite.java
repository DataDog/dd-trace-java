package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.propagation.StringModule;
import datadog.trace.util.stacktrace.StackUtils;
import java.util.ArrayList;
import java.util.List;
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
    final StringModule module = InstrumentationBridge.STRING;
    try {
      if (module != null) {
        module.onStringConcat(self, param, result);
      }
    } catch (final Throwable e) {
      module.onUnexpectedException("afetConcat threw", e);
    }
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.substring(int)")
  public static String afterSubstring(
      @CallSite.This final String self,
      @CallSite.Argument final int beginIndex,
      @CallSite.Return final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    try {
      if (module != null) {
        module.onStringSubSequence(self, beginIndex, self != null ? self.length() : 0, result);
      }
    } catch (final Throwable e) {
      module.onUnexpectedException("afterSubstring threw", e);
    }
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.substring(int, int)")
  public static String afterSubstring(
      @CallSite.This final String self,
      @CallSite.Argument final int beginIndex,
      @CallSite.Argument final int endIndex,
      @CallSite.Return final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    try {
      if (module != null) {
        module.onStringSubSequence(self, beginIndex, endIndex, result);
      }
    } catch (final Throwable e) {
      module.onUnexpectedException("afterSubstring threw", e);
    }
    return result;
  }

  @CallSite.After("java.lang.CharSequence java.lang.String.subSequence(int, int)")
  public static CharSequence afterSubSequence(
      @CallSite.This final String self,
      @CallSite.Argument final int beginIndex,
      @CallSite.Argument final int endIndex,
      @CallSite.Return final CharSequence result) {
    final StringModule module = InstrumentationBridge.STRING;
    try {
      if (module != null) {
        module.onStringSubSequence(self, beginIndex, endIndex, result);
      }
    } catch (final Throwable e) {
      module.onUnexpectedException("afterSubSequence threw", e);
    }
    return result;
  }

  @CallSite.After(
      "java.lang.String java.lang.String.join(java.lang.CharSequence, java.lang.CharSequence[])")
  public static String afterJoin(
      @CallSite.Argument final CharSequence delimiter,
      @CallSite.Argument final CharSequence[] elements,
      @CallSite.Return final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    try {
      if (module != null) {
        module.onStringJoin(result, delimiter, elements);
      }
    } catch (final Throwable e) {
      module.onUnexpectedException("afterJoin threw", e);
    }
    return result;
  }

  @CallSite.Around(
      "java.lang.String java.lang.String.join(java.lang.CharSequence, java.lang.Iterable)")
  public static String aroundJoin(
      @CallSite.Argument final CharSequence delimiter,
      @CallSite.Argument final Iterable<? extends CharSequence> elements)
      throws Throwable {
    // Iterate the iterable to guarantee the default behavior for custom mutable Iterables
    List<CharSequence> copy = new ArrayList<>();
    String result;
    try {
      elements.forEach(copy::add);
      result = String.join(delimiter, copy);
    } catch (final Throwable e) {
      throw StackUtils.filterDatadog(e);
    }
    final StringModule module = InstrumentationBridge.STRING;
    try {
      if (module != null) {
        module.onStringJoin(result, delimiter, copy.toArray(new CharSequence[copy.size()]));
      }
    } catch (final Throwable e) {
      module.onUnexpectedException("afterSubSequence threw", e);
    }
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.toUpperCase()")
  public static String afterToUppercase(
      @CallSite.This final String self, @CallSite.Return final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    try {
      if (module != null) {
        module.onStringToUpperCase(self, result);
      }
    } catch (final Throwable e) {
      module.onUnexpectedException("afterToUppercase threw", e);
    }
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.toUpperCase(java.util.Locale)")
  public static String afterToUppercase(
      @CallSite.This final String self,
      @CallSite.Argument final Locale locale,
      @CallSite.Return final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    try {
      if (module != null) {
        module.onStringToUpperCase(self, result);
      }
    } catch (final Throwable e) {
      module.onUnexpectedException("afterToUppercase threw", e);
    }
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.toLowerCase()")
  public static String afterToLowerCase(
      @CallSite.This final String self, @CallSite.Return final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    try {
      if (module != null) {
        module.onStringToLowerCase(self, result);
      }
    } catch (final Throwable e) {
      module.onUnexpectedException("afterToLowerCase threw", e);
    }
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.toLowerCase(java.util.Locale)")
  public static String afterToLowercase(
      @CallSite.This final String self,
      @CallSite.Argument final Locale locale,
      @CallSite.Return final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    try {
      if (module != null) {
        module.onStringToLowerCase(self, result);
      }
    } catch (final Throwable e) {
      module.onUnexpectedException("afterToLowerCase threw", e);
    }
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.trim()")
  public static String afterTrim(
      @CallSite.This final String self, @CallSite.Return final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    try {
      if (module != null) {
        module.onStringTrim(self, result);
      }
    } catch (final Throwable e) {
      module.onUnexpectedException("afetConcat threw", e);
    }
    return result;
  }
}
