package datadog.trace.instrumentation.commonscodec;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.CodecModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.codec.binary.Base64;

@Propagation
@CallSite(spi = IastCallSites.class)
// TODO complete propagation support
public class Base64CallSite {

  @CallSite.After("byte[] org.apache.commons.codec.binary.Base64.encodeBase64(byte[])")
  @Nonnull
  public static byte[] afterEncodeBase64(
      @CallSite.Argument @Nullable final byte[] param,
      @CallSite.Return @Nonnull final byte[] result) {
    final CodecModule module = InstrumentationBridge.CODEC;
    if (module != null) {
      try {
        module.onBase64Encode(param, result);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterEncodeBase64 threw", e);
      }
    }
    return result;
  }

  @CallSite.After("byte[] org.apache.commons.codec.binary.Base64.encode(byte[])")
  @Nonnull
  public static byte[] afterEncode(
      @CallSite.This final Base64 codec,
      @CallSite.Argument @Nullable final byte[] param,
      @CallSite.Return @Nonnull final byte[] result) {
    final CodecModule module = InstrumentationBridge.CODEC;
    if (module != null) {
      try {
        module.onBase64Encode(param, result);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterEncode threw", e);
      }
    }
    return result;
  }

  @CallSite.After("byte[] org.apache.commons.codec.binary.Base64.decodeBase64(byte[])")
  @Nonnull
  public static byte[] afterDecodeBase64(
      @CallSite.Argument @Nullable final byte[] param,
      @CallSite.Return @Nonnull final byte[] result) {
    final CodecModule module = InstrumentationBridge.CODEC;
    if (module != null) {
      try {
        module.onBase64Decode(param, result);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterDecodeBase64 threw", e);
      }
    }
    return result;
  }

  @CallSite.After("byte[] org.apache.commons.codec.binary.Base64.decode(byte[])")
  @Nonnull
  public static byte[] afterDecode(
      @CallSite.This final Base64 codec,
      @CallSite.Argument @Nullable final byte[] param,
      @CallSite.Return @Nonnull final byte[] result) {
    final CodecModule module = InstrumentationBridge.CODEC;
    if (module != null) {
      try {
        module.onBase64Decode(param, result);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterDecode threw", e);
      }
    }
    return result;
  }
}
