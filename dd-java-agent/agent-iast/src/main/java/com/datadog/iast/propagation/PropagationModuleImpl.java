package com.datadog.iast.propagation;

import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.IastModuleBase;
import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Range;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.taint.TaintedObject;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.propagation.PropagationModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class PropagationModuleImpl extends IastModuleBase implements PropagationModule {
  @Override
  public void taintIfInputIsTainted(@Nullable Object toTaint, @Nullable Object input) {
    if (toTaint == null || input == null) {
      return;
    }
    taintWithSameRanges(input, toTaint);
  }

  @Override
  public void taintIfInputIsTainted(@Nullable String toTaint, @Nullable Object input) {
    if (!canBeTainted(toTaint) || input == null) {
      return;
    }
    taintWithFullyTaintedRange(input, toTaint);
  }

  @Override
  public void taintIfInputIsTainted(@Nonnull Object toTaint, @Nullable String input) {
    if (toTaint == null || !canBeTainted(input)) {
      return;
    }
    taintWithMaxRange(input, toTaint);
  }

  private void taintWithSameRanges(final Object input, final Object toTaint) {
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    TaintedObject tainted = taintedObjects.get(input);
    if (tainted != null) {
      taintedObjects.taint(toTaint, tainted.getRanges());
    }
  }

  private void taintWithFullyTaintedRange(final Object input, final String toTaint) {
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    TaintedObject tainted = taintedObjects.get(input);
    if (tainted != null) {
      Range[] ranges = tainted.getRanges();
      if (ranges.length == 1) {
        taintedObjects.taint(
            toTaint, Ranges.forString(toTaint, tainted.getRanges()[0].getSource()));
      }
    }
  }

  private void taintWithMaxRange(final String input, final Object toTaint) {
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    TaintedObject tainted = taintedObjects.get(input);
    if (tainted != null) {
      Range[] ranges = tainted.getRanges();
      if (ranges.length != 0) {
        taintedObjects.taint(toTaint, Ranges.forObject(tainted.getRanges()[0].getSource()));
      }
    }
  }
}
