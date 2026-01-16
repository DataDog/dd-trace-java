package datadog.trace.instrumentation.java.lang;

import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.CodecModule;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.nio.charset.Charset;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@Propagation
@CallSite(
    spi = IastCallSites.class,
    enabled = {"datadog.trace.api.iast.IastEnabledChecks", "isFullDetection"})
public class StringFullDetectionCallSite {

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
}
