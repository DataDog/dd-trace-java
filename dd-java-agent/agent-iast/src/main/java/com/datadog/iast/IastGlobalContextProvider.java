package com.datadog.iast;

import com.datadog.iast.taint.NativeTaintedObjectsAdapter;
import com.datadog.iast.taint.TaintedMap;
import com.datadog.iast.taint.TaintedObjectsMap;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.taint.TaintedObjects;
import datadog.trace.api.nagent.NativeAgent;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class IastGlobalContextProvider extends IastContext.Provider {

  // (16384 * 4) buckets: approx 256K
  private static final int MAP_SIZE = TaintedMap.DEFAULT_CAPACITY * (1 << 2);
  private static final int MAX_AGE = TaintedMap.DEFAULT_MAX_AGE;
  private static final TimeUnit MAX_AGE_UNIT = TaintedMap.DEFAULT_MAX_AGE_UNIT;

  // Map that with purge option
  private final TaintedObjects taintedObjects;

  public IastGlobalContextProvider() {
    TaintedObjects to =
        TaintedObjectsMap.build(TaintedMap.buildWithPurge(MAP_SIZE, MAX_AGE, MAX_AGE_UNIT));
    if (NativeAgent.isInstalled()) {
      to = new NativeTaintedObjectsAdapter(to);
    }
    taintedObjects = to;
  }

  @Nullable
  @Override
  public TaintedObjects resolveTaintedObjects() {
    return taintedObjects;
  }

  @Override
  public IastContext buildRequestContext() {
    return new IastRequestContext(taintedObjects);
  }

  @Override
  public void releaseRequestContext(@Nonnull final IastContext context) {
    // nothing to release in global mode
  }
}
