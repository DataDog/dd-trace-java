package com.datadog.iast.propagation;

import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.model.Range;
import com.datadog.iast.taint.TaintedObject;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.propagation.CodecModule;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public abstract class BaseCodecModule implements CodecModule {

  @Override
  public void onUrlDecode(
      @Nonnull final String value, @Nullable final String encoding, @Nonnull final String result) {
    if (!canBeTainted(result)) {
      return;
    }
    taintIfInputIsTainted(
        result, value, tainted -> urlDecodeRanges(value, encoding, result, tainted.getRanges()));
  }

  @Override
  public void onStringFromBytes(
      @Nonnull final byte[] value, @Nullable final String charset, @Nonnull final String result) {
    if (!canBeTainted(result)) {
      return;
    }
    taintIfInputIsTainted(
        result, value, tainted -> fromBytesRanges(value, charset, result, tainted.getRanges()));
  }

  @Override
  public void onStringGetBytes(
      @Nonnull final String value, @Nullable final String charset, @Nonnull final byte[] result) {
    if (result == null || result.length == 0) {
      return;
    }
    taintIfInputIsTainted(
        result, value, tainted -> getBytesRanges(value, charset, result, tainted.getRanges()));
  }

  @Override
  public void onBase64Encode(@Nullable byte[] value, @Nullable byte[] result) {
    if (value == null || result == null || result.length == 0) {
      return;
    }
    taintIfInputIsTainted(
        result, value, tainted -> encodeBase64Ranges(value, result, tainted.getRanges()));
  }

  @Override
  public void onBase64Decode(@Nullable byte[] value, @Nullable byte[] result) {
    if (value == null || result == null || result.length == 0) {
      return;
    }
    taintIfInputIsTainted(
        result, value, tainted -> decodeBase64Ranges(value, result, tainted.getRanges()));
  }

  private static void taintIfInputIsTainted(
      final Object value, final Object input, final Function<TaintedObject, Range[]> mapper) {
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    final TaintedObject tainted = to.get(input);
    if (hasRanges(tainted)) {
      final Range[] ranges = mapper.apply(tainted);
      if (hasRanges(ranges)) {
        to.taint(value, ranges);
      }
    }
  }

  private static boolean hasRanges(@Nullable final TaintedObject tainted) {
    return tainted != null && hasRanges(tainted.getRanges());
  }

  private static boolean hasRanges(@Nullable final Range[] ranges) {
    return ranges != null && ranges.length > 0;
  }

  protected abstract Range[] urlDecodeRanges(
      final @Nonnull String value,
      final @Nullable String charset,
      @Nonnull final String result,
      @Nonnull final Range[] ranges);

  protected abstract Range[] fromBytesRanges(
      final @Nonnull byte[] value,
      final @Nullable String charset,
      @Nonnull final String result,
      @Nonnull final Range[] ranges);

  protected abstract Range[] getBytesRanges(
      final @Nonnull String value,
      final @Nullable String charset,
      @Nonnull final byte[] result,
      @Nonnull final Range[] ranges);

  protected abstract Range[] decodeBase64Ranges(
      final @Nonnull byte[] value, @Nonnull final byte[] result, @Nonnull final Range[] ranges);

  protected abstract Range[] encodeBase64Ranges(
      final @Nonnull byte[] value, @Nonnull final byte[] result, @Nonnull final Range[] ranges);
}
