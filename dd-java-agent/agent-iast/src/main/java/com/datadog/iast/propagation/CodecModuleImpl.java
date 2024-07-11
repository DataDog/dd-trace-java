package com.datadog.iast.propagation;

import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;

import datadog.trace.api.iast.propagation.CodecModule;
import datadog.trace.api.iast.propagation.PropagationModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class CodecModuleImpl implements CodecModule {

  private final PropagationModule propagationModule;

  public CodecModuleImpl() {
    this(new PropagationModuleImpl());
  }

  CodecModuleImpl(final PropagationModule propagationModule) {
    this.propagationModule = propagationModule;
  }

  @Override
  public void onUrlDecode(
      @Nonnull final String value, @Nullable final String encoding, @Nonnull final String result) {
    propagationModule.taintStringIfTainted(result, value);
  }

  @Override
  public void onStringFromBytes(
      @Nonnull final byte[] value,
      int offset,
      int length,
      @Nullable final String charset,
      @Nonnull final String result) {
    // create a new range shifted to the result string coordinates
    propagationModule.taintStringIfRangeTainted(result, value, offset, length, false, NOT_MARKED);
  }

  @Override
  public void onStringGetBytes(
      @Nonnull final String value, @Nullable final String charset, @Nonnull final byte[] result) {
    propagationModule.taintObjectIfTainted(result, value);
  }

  @Override
  public void onBase64Encode(@Nullable byte[] value, @Nullable byte[] result) {
    propagationModule.taintObjectIfTainted(result, value);
  }

  @Override
  public void onBase64Decode(@Nullable byte[] value, @Nullable byte[] result) {
    propagationModule.taintObjectIfTainted(result, value);
  }
}
