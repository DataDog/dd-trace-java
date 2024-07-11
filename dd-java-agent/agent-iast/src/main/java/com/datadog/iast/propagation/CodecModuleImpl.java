package com.datadog.iast.propagation;

import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;
import static datadog.trace.api.iast.VulnerabilityMarks.XSS_MARK;

import com.datadog.iast.taint.TaintedObject;
import com.datadog.iast.taint.TaintedObjects;
import com.datadog.iast.util.RangeBuilder;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.propagation.CodecModule;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.net.URI;
import java.net.URL;
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
  public void onUrlEncode(
      @Nonnull final String value, @Nullable final String encoding, @Nonnull final String result) {
    // the new string should be safe to be used in
    propagationModule.taintStringIfTainted(result, value, false, XSS_MARK);
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

  @Override
  public void onUriCreate(@Nonnull final URI result, final Object... args) {
    taintUrlIfAnyTainted(result, args);
  }

  @Override
  public void onUrlCreate(@Nonnull final URL result, final Object... args) {
    taintUrlIfAnyTainted(result, args);
  }

  private void taintUrlIfAnyTainted(@Nonnull final Object url, @Nonnull final Object... args) {
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    final String toString = url.toString();
    final RangeBuilder builder = new RangeBuilder();
    for (final Object arg : args) {
      final TaintedObject tainted = to.get(arg);
      if (tainted != null) {
        final int offset = toString.indexOf(arg.toString());
        if (offset >= 0) {
          builder.add(tainted.getRanges(), offset);
        }
      }
    }
    if (builder.isEmpty()) {
      return;
    }
    to.taint(url, builder.toArray());
  }
}
