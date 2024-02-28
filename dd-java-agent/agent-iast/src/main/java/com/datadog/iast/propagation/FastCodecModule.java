package com.datadog.iast.propagation;

import datadog.trace.api.iast.propagation.CodecModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class FastCodecModule extends PropagationModuleImpl implements CodecModule {

  @Override
  public void onUrlDecode(
      @Nonnull final String value, @Nullable final String encoding, @Nonnull final String result) {
    taintIfTainted(result, value);
  }

  @Override
  public void onStringFromBytes(
      @Nonnull final byte[] value, @Nullable final String charset, @Nonnull final String result) {
    taintIfTainted(result, value);
  }

  @Override
  public void onStringGetBytes(
      @Nonnull final String value, @Nullable final String charset, @Nonnull final byte[] result) {
    taintIfTainted(result, value);
  }

  @Override
  public void onBase64Encode(@Nullable byte[] value, @Nullable byte[] result) {
    taintIfTainted(result, value);
  }

  @Override
  public void onBase64Decode(@Nullable byte[] value, @Nullable byte[] result) {
    taintIfTainted(result, value);
  }
}
