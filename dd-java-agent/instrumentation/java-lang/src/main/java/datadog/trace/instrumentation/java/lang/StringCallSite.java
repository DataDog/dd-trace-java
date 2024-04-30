package datadog.trace.instrumentation.java.lang;

import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.CodecModule;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.propagation.StringModule;
import datadog.trace.util.stacktrace.StackUtils;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@Propagation
@CallSite(spi = IastCallSites.class)
public class StringCallSite {

  @CallSite.After("java.lang.String java.lang.String.concat(java.lang.String)")
  @Nonnull
  public static String afterConcat(
      @CallSite.This @Nonnull final String self,
      @CallSite.Argument @Nullable final String param,
      @CallSite.Return @Nonnull final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    if (module != null) {
      try {
        module.onStringConcat(self, param, result);
      } catch (final Throwable e) {
        module.onUnexpectedException("afetConcat threw", e);
      }
    }
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.substring(int)")
  public static String afterSubstring(
      @CallSite.This final String self,
      @CallSite.Argument final int beginIndex,
      @CallSite.Return final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    if (module != null) {
      try {
        module.onStringSubSequence(self, beginIndex, self != null ? self.length() : 0, result);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterSubstring threw", e);
      }
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
    if (module != null) {
      try {
        module.onStringSubSequence(self, beginIndex, endIndex, result);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterSubstring threw", e);
      }
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
    if (module != null) {
      try {
        module.onStringSubSequence(self, beginIndex, endIndex, result);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterSubSequence threw", e);
      }
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
    if (module != null) {
      try {
        module.onStringJoin(result, delimiter, elements);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterJoin threw", e);
      }
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
    if (module != null) {
      try {
        module.onStringJoin(result, delimiter, copy.toArray(new CharSequence[0]));
      } catch (final Throwable e) {
        module.onUnexpectedException("afterSubSequence threw", e);
      }
    }
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.toUpperCase()")
  public static String afterToUpperCase(
      @CallSite.This final String self, @CallSite.Return final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    if (module != null) {
      try {
        module.onStringToUpperCase(self, result);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterToUppercase threw", e);
      }
    }
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.toUpperCase(java.util.Locale)")
  public static String afterToUpperCase(
      @CallSite.This final String self,
      @CallSite.Argument final Locale locale,
      @CallSite.Return final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    if (module != null) {
      try {
        module.onStringToUpperCase(self, result);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterToUppercase threw", e);
      }
    }
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.toLowerCase()")
  public static String afterToLowerCase(
      @CallSite.This final String self, @CallSite.Return final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    if (module != null) {
      try {
        module.onStringToLowerCase(self, result);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterToLowerCase threw", e);
      }
    }
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.toLowerCase(java.util.Locale)")
  public static String afterToLowerCase(
      @CallSite.This final String self,
      @CallSite.Argument final Locale locale,
      @CallSite.Return final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    if (module != null) {
      try {
        module.onStringToLowerCase(self, result);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterToLowerCase threw", e);
      }
    }
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.trim()")
  public static String afterTrim(
      @CallSite.This final String self, @CallSite.Return final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    if (module != null) {
      try {
        module.onStringTrim(self, result);
      } catch (final Throwable e) {
        module.onUnexpectedException("afetConcat threw", e);
      }
    }
    return result;
  }

  @CallSite.After("void java.lang.String.<init>(java.lang.String)")
  public static String afterStringConstructor(
      @CallSite.AllArguments @Nonnull final Object[] params,
      @CallSite.Return @Nonnull final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    try {
      if (module != null) {
        module.onStringConstructor((String) params[0], result);
      }
    } catch (final Throwable e) {
      module.onUnexpectedException("afterStringConstructor threw", e);
    }
    return result;
  }

  @CallSite.After("void java.lang.String.<init>(byte[])")
  public static String afterByteArrayCtor(
      @CallSite.AllArguments @Nonnull final Object[] params,
      @CallSite.Return @Nonnull final String result) {
    final CodecModule module = InstrumentationBridge.CODEC;
    try {
      if (module != null) {
        final byte[] bytes = (byte[]) params[0];
        if (bytes != null) {
          module.onStringFromBytes(bytes, 0, bytes.length, null, result);
        }
      }
    } catch (final Throwable e) {
      module.onUnexpectedException("afterByteArrayCtor threw", e);
    }
    return result;
  }

  @CallSite.After("void java.lang.String.<init>(byte[], java.lang.String)")
  @CallSite.After("void java.lang.String.<init>(byte[], java.nio.charset.Charset)")
  public static String afterByteArrayCtor2(
      @CallSite.AllArguments @Nonnull final Object[] params,
      @CallSite.Return @Nonnull final String result) {
    final CodecModule module = InstrumentationBridge.CODEC;
    try {
      if (module != null) {
        final byte[] bytes = (byte[]) params[0];
        if (bytes != null) {
          final String charset =
              params[1] instanceof Charset ? ((Charset) params[1]).name() : (String) params[1];
          module.onStringFromBytes(bytes, 0, bytes.length, charset, result);
        }
      }
    } catch (final Throwable e) {
      module.onUnexpectedException("afterByteArrayCtor2 threw", e);
    }
    return result;
  }

  @CallSite.After("void java.lang.String.<init>(byte[], int, int)")
  public static String afterByteArrayCtor3(
      @CallSite.AllArguments @Nonnull final Object[] params,
      @CallSite.Return @Nonnull final String result) {
    final CodecModule module = InstrumentationBridge.CODEC;
    try {
      if (module != null) {
        final byte[] bytes = (byte[]) params[0];
        if (bytes != null) {
          module.onStringFromBytes(bytes, (int) params[1], (int) params[2], null, result);
        }
      }
    } catch (final Throwable e) {
      module.onUnexpectedException("afterByteArrayCtor3 threw", e);
    }
    return result;
  }

  @CallSite.After("void java.lang.String.<init>(byte[], int, int, java.lang.String)")
  @CallSite.After("void java.lang.String.<init>(byte[], int, int, java.nio.charset.Charset)")
  public static String afterByteArrayCtor4(
      @CallSite.AllArguments @Nonnull final Object[] params,
      @CallSite.Return @Nonnull final String result) {
    final CodecModule module = InstrumentationBridge.CODEC;
    try {
      if (module != null) {
        final byte[] bytes = (byte[]) params[0];
        if (bytes != null) {
          final String charset =
              params[3] instanceof Charset ? ((Charset) params[3]).name() : (String) params[3];
          module.onStringFromBytes(bytes, (int) params[1], (int) params[2], charset, result);
        }
      }
    } catch (final Throwable e) {
      module.onUnexpectedException("afterByteArrayCtor4 threw", e);
    }
    return result;
  }

  @CallSite.After("byte[] java.lang.String.getBytes()")
  public static byte[] afterGetBytes(
      @CallSite.This @Nonnull final String self, @CallSite.Return @Nonnull final byte[] result) {
    final CodecModule module = InstrumentationBridge.CODEC;
    try {
      if (module != null) {
        module.onStringGetBytes(self, null, result);
      }
    } catch (final Throwable e) {
      module.onUnexpectedException("afterGetBytes threw", e);
    }
    return result;
  }

  @CallSite.After("byte[] java.lang.String.getBytes(java.lang.String)")
  public static byte[] afterGetBytes(
      @CallSite.This @Nonnull final String self,
      @CallSite.Argument @Nullable final String encoding,
      @CallSite.Return @Nonnull final byte[] result) {
    final CodecModule module = InstrumentationBridge.CODEC;
    try {
      if (module != null) {
        module.onStringGetBytes(self, encoding, result);
      }
    } catch (final Throwable e) {
      module.onUnexpectedException("afterGetBytes threw", e);
    }
    return result;
  }

  @CallSite.After("byte[] java.lang.String.getBytes(java.nio.charset.Charset)")
  public static byte[] afterGetBytes(
      @CallSite.This @Nonnull final String self,
      @CallSite.Argument @Nullable final Charset encoding,
      @CallSite.Return @Nonnull final byte[] result) {
    final CodecModule module = InstrumentationBridge.CODEC;
    try {
      if (module != null) {
        module.onStringGetBytes(self, encoding == null ? null : encoding.name(), result);
      }
    } catch (final Throwable e) {
      module.onUnexpectedException("afterGetBytes threw", e);
    }
    return result;
  }

  @CallSite.After("java.lang.String java.lang.String.format(java.lang.String, java.lang.Object[])")
  public static String afterFormat(
      @CallSite.Argument(0) @Nullable final String pattern,
      @CallSite.Argument(1) @Nonnull final Object[] args,
      @CallSite.Return @Nonnull final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    try {
      if (module != null && pattern != null) {
        module.onStringFormat(pattern, args, result);
      }
    } catch (final Throwable e) {
      module.onUnexpectedException("afterFormat threw", e);
    }
    return result;
  }

  @CallSite.After(
      "java.lang.String java.lang.String.format(java.util.Locale, java.lang.String, java.lang.Object[])")
  public static String afterFormat(
      @CallSite.Argument(0) @Nullable final Locale locale,
      @CallSite.Argument(1) @Nullable final String pattern,
      @CallSite.Argument(2) @Nonnull final Object[] args,
      @CallSite.Return @Nonnull final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    try {
      if (module != null && pattern != null) {
        module.onStringFormat(locale, pattern, args, result);
      }
    } catch (final Throwable e) {
      module.onUnexpectedException("afterFormat threw", e);
    }
    return result;
  }

  @CallSite.After("char[] java.lang.String.toCharArray()")
  public static char[] afterToCharArray(
      @CallSite.This @Nonnull final String self, @CallSite.Return @Nonnull final char[] result) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taintObjectIfTainted(result, self, true, NOT_MARKED);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterToCharArray threw", e);
      }
    }
    return result;
  }

  @CallSite.After("java.lang.String[] java.lang.String.split(java.lang.String)")
  public static String[] afterSplit(
      @CallSite.This @Nonnull final String self,
      @CallSite.Argument(0) @Nonnull final String regex,
      @CallSite.Return @Nonnull final String[] result) {
    final StringModule module = InstrumentationBridge.STRING;
    if (module != null) {
      try {
        module.onSplit(self, result);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterSplit threw", e);
      }
    }
    return result;
  }

  @CallSite.After("java.lang.String[] java.lang.String.split(java.lang.String, int)")
  public static String[] afterSplitWithLimit(
      @CallSite.This @Nonnull final String self,
      @CallSite.Argument(0) @Nonnull final String regex,
      @CallSite.Argument(1) final int pos,
      @CallSite.Return @Nonnull final String[] result) {
    final StringModule module = InstrumentationBridge.STRING;
    if (module != null) {
      try {
        module.onSplit(self, result);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterSplit threw", e);
      }
    }
    return result;
  }
}
