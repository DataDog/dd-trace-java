package com.datadog.iast.propagation;

import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;

import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.propagation.CodecModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class FastCodecModule extends PropagationModuleImpl implements CodecModule {

  @Override
  public void onUrlDecode(
      @Nonnull final String value, @Nullable final String encoding, @Nonnull final String result) {
    final IastContext ctx = IastContext.Provider.get();
    if (ctx != null) {
      taintIfTainted(ctx, result, value);
    }
  }

  @Override
  public void onStringFromBytes(
      @Nonnull final byte[] value,
      int offset,
      int length,
      @Nullable final String charset,
      @Nonnull final String result) {
    // create a new range shifted to the result string coordinates
    final IastContext ctx = IastContext.Provider.get();
    if (ctx != null) {
      taintIfTainted(ctx, result, value, offset, length, false, NOT_MARKED);
    }
  }

  @Override
  public void onStringGetBytes(
      @Nonnull final String value, @Nullable final String charset, @Nonnull final byte[] result) {
    final IastContext ctx = IastContext.Provider.get();
    if (ctx != null) {
      taintIfTainted(ctx, result, value);
    }
  }

  @Override
  public void onBase64Encode(@Nullable byte[] value, @Nullable byte[] result) {
    final IastContext ctx = IastContext.Provider.get();
    if (ctx != null) {
      taintIfTainted(ctx, result, value);
    }
  }

  @Override
  public void onBase64Decode(@Nullable byte[] value, @Nullable byte[] result) {
    final IastContext ctx = IastContext.Provider.get();
    if (ctx != null) {
      taintIfTainted(ctx, result, value);
    }
  }
}
