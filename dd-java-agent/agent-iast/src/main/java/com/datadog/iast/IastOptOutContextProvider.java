package com.datadog.iast;

import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.taint.TaintedObjects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class IastOptOutContextProvider extends IastContext.Provider {

  @Nullable
  @Override
  public TaintedObjects resolveTaintedObjects() {
    return TaintedObjects.NoOp.INSTANCE;
  }

  @Override
  public IastContext buildRequestContext() {
    return new IastRequestContext(TaintedObjects.NoOp.INSTANCE);
  }

  @Override
  public void releaseRequestContext(@Nonnull final IastContext context) {
    // nothing to release in opt out mode
  }
}
