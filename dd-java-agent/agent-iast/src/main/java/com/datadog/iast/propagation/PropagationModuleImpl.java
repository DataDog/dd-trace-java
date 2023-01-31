package com.datadog.iast.propagation;

import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.IastModuleBase;
import com.datadog.iast.IastRequestContext;
import com.datadog.iast.taint.TaintedObject;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.propagation.PropagationModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class PropagationModuleImpl extends IastModuleBase implements PropagationModule {
  @Override
  public void taintIfInputIsTainted(@Nullable Object param1, @Nullable Object param2) {
    if (param1 == null || param2 == null) {
      return;
    }
    taint(param2, param1);
  }

  @Override
  public void taintIfInputIsTainted(@Nullable String param1, @Nullable Object param2) {
    if (!canBeTainted(param1) || param2 == null) {
      return;
    }
    taint(param2, param1);
  }

  @Override
  public void taintIfInputIsTainted(@Nonnull Object param1, @Nullable String param2) {
    if (param1 == null || !canBeTainted(param2)) {
      return;
    }
    taint(param2, param1);
  }

  private void taint(final Object toEval, final Object toTaint) {
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    TaintedObject tainted = taintedObjects.get(toEval);
    if (tainted != null) {
      taintedObjects.taint(toTaint, tainted.getRanges());
    }
  }
}
