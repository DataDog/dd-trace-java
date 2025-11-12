package com.datadog.iast;

import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.IastContext;
import java.io.IOException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class IastOptOutContext implements IastContext {

  @Nonnull
  @SuppressWarnings("unchecked")
  @Override
  public TaintedObjects getTaintedObjects() {
    return TaintedObjects.NoOp.INSTANCE;
  }

  @Override
  public void close() throws IOException {}

  public static class Provider extends IastContext.Provider {

    final IastContext optOutContext = new IastOptOutContext();

    @Nullable
    @Override
    public IastContext resolve() {
      return optOutContext;
    }

    @Override
    public IastContext buildRequestContext() {
      return new IastRequestContext(optOutContext.getTaintedObjects());
    }

    @Override
    public void releaseRequestContext(@Nonnull final IastContext context) {
      // nothing to release in opt out mode
    }
  }
}
