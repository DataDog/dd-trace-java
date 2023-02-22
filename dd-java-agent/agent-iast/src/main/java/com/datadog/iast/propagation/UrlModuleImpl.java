package com.datadog.iast.propagation;

import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.IastModuleBase;
import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Range;
import com.datadog.iast.taint.TaintedObject;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.propagation.UrlModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class UrlModuleImpl extends IastModuleBase implements UrlModule {
  @Override
  public void onDecode(
      @Nonnull final String value, @Nullable final String encoding, @Nonnull final String result) {
    if (!canBeTainted(result)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    final TaintedObject tainted = to.get(value);
    if (tainted != null && tainted.getRanges().length > 0) {
      // TODO the resulting ranges can change due to the decoding process
      final Range range = tainted.getRanges()[0];
      to.taint(result, new Range[] {new Range(0, result.length(), range.getSource())});
    }
  }
}
