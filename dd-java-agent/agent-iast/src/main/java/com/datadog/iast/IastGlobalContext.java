package com.datadog.iast;

import com.datadog.iast.taint.TaintedMap;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.IastContext;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class IastGlobalContext implements IastContext {

  private final TaintedObjects taintedObjects;

  public IastGlobalContext(final TaintedObjects taintedObjects) {
    this.taintedObjects = taintedObjects;
  }

  @SuppressWarnings("unchecked")
  @Nonnull
  @Override
  public TaintedObjects getTaintedObjects() {
    return taintedObjects;
  }

  @Override
  public void close() throws IOException {}

  public static class Provider extends IastContext.Provider {

    // (16384 * 4) buckets: approx 256K
    static final int MAP_SIZE = TaintedMap.DEFAULT_CAPACITY * (1 << 2);
    static final int MAX_AGE = TaintedMap.DEFAULT_MAX_AGE;
    static final TimeUnit MAX_AGE_UNIT = TaintedMap.DEFAULT_MAX_AGE_UNIT;

    // Map that with purge option
    final IastContext globalContext =
        new IastGlobalContext(
            TaintedObjects.build(TaintedMap.buildWithPurge(MAP_SIZE, MAX_AGE, MAX_AGE_UNIT)));

    @Nullable
    @Override
    public IastContext resolve() {
      return globalContext;
    }

    @Override
    public IastContext buildRequestContext() {
      return new IastRequestContext((TaintedObjects) globalContext.getTaintedObjects());
    }

    @Override
    public void releaseRequestContext(@Nonnull final IastContext context) {
      // nothing to release in global mode
    }
  }
}
