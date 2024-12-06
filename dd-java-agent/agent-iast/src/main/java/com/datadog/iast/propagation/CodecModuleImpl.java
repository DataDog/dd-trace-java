package com.datadog.iast.propagation;

import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;
import static datadog.trace.api.iast.VulnerabilityMarks.XSS_MARK;

import com.datadog.iast.util.RangeBuilder;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.propagation.CodecModule;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.taint.TaintedObject;
import datadog.trace.api.iast.taint.TaintedObjects;
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
    final TaintedObjects to = IastContext.Provider.taintedObjects();
    propagationModule.taintObjectIfTainted(to, result, value);
  }

  @Override
  public void onUrlEncode(
      @Nonnull final String value, @Nullable final String encoding, @Nonnull final String result) {
    // the new string should be safe to be used in
    final TaintedObjects to = IastContext.Provider.taintedObjects();
    propagationModule.taintObjectIfTainted(to, result, value, false, XSS_MARK);
  }

  @Override
  public void onStringFromBytes(
      @Nonnull final byte[] value,
      int offset,
      int length,
      @Nullable final String charset,
      @Nonnull final String result) {
    // create a new range shifted to the result string coordinates
    final TaintedObjects to = IastContext.Provider.taintedObjects();
    propagationModule.taintObjectIfRangeTainted(
        to, result, value, offset, length, false, NOT_MARKED);
  }

  @Override
  public void onStringGetBytes(
      @Nonnull final String value, @Nullable final String charset, @Nonnull final byte[] result) {
    final TaintedObjects to = IastContext.Provider.taintedObjects();
    propagationModule.taintObjectIfTainted(to, result, value);
  }

  @Override
  public void onBase64Encode(@Nullable byte[] value, @Nullable byte[] result) {
    final TaintedObjects to = IastContext.Provider.taintedObjects();
    propagationModule.taintObjectIfTainted(to, result, value);
  }

  @Override
  public void onBase64Decode(@Nullable byte[] value, @Nullable byte[] result) {
    final TaintedObjects to = IastContext.Provider.taintedObjects();
    propagationModule.taintObjectIfTainted(to, result, value);
  }

  @Override
  public void onUriCreate(@Nonnull final URI result, final Object... args) {
    final TaintedObjects to = IastContext.Provider.taintedObjects();
    if (to == null) {
      return;
    }
    taintUrlIfAnyTainted(to, result, args);
  }

  @Override
  public void onUrlCreate(@Nonnull final URL result, final Object... args) {
    final TaintedObjects to = IastContext.Provider.taintedObjects();
    if (to == null) {
      return;
    }
    if (args.length > 0 && args[0] instanceof URL) {
      final TaintedObject tainted = to.get(args[0]);
      if (tainted != null) {
        final URL context = (URL) args[0];
        // TODO improve matching when using a URL context
        // Checking if the toString representation of the context is present in the final URL is a
        // bit fragile, the spec might override some of it's parts (e.g. the final file). We can get
        // away with this as having a context with tainted values is probably pretty rare.
        if (!context.getProtocol().equals(result.getProtocol())
            || !context.getHost().equals(result.getHost())) {
          args[0] = null;
        }
      }
    }
    taintUrlIfAnyTainted(to, result, args);
  }

  private void taintUrlIfAnyTainted(
      @Nonnull final TaintedObjects to, @Nonnull final Object url, @Nonnull final Object... args) {
    final String toString = url.toString();
    final RangeBuilder builder = new RangeBuilder();
    boolean hasTainted = false;
    for (final Object arg : args) {
      final TaintedObject tainted = to.get(arg);
      if (tainted != null) {
        hasTainted = true;
        final int offset = toString.indexOf(arg.toString());
        if (offset >= 0) {
          builder.add(tainted.getRanges(), offset);
        }
      }
    }
    if (!hasTainted) {
      return;
    }
    if (builder.isEmpty()) {
      // no mappings of tainted values in the URL, resort to fully tainting it
      propagationModule.taintObjectIfAnyTainted(to, url, args);
    } else {
      to.taint(url, builder.toArray());
    }
  }
}
