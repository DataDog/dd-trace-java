package com.datadog.iast.telemetry.taint;

import static datadog.trace.api.iast.telemetry.IastMetric.EXECUTED_TAINTED;
import static datadog.trace.api.iast.telemetry.IastMetric.REQUEST_TAINTED;

import com.datadog.iast.model.Range;
import com.datadog.iast.taint.TaintedObject;
import com.datadog.iast.taint.TaintedObjects;
import com.datadog.iast.util.Wrapper;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.telemetry.IastMetricCollector;
import datadog.trace.api.iast.telemetry.Verbosity;
import java.util.Iterator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class TaintedObjectsWithTelemetry implements TaintedObjects, Wrapper<TaintedObjects> {

  public static TaintedObjects build(final Verbosity verbosity, final IastContext ctx) {
    final TaintedObjects delegate = ctx.getTaintedObjects();
    if (verbosity.isInformationEnabled()) {
      return new TaintedObjectsWithTelemetry(verbosity.isDebugEnabled(), delegate, ctx);
    }
    return delegate;
  }

  private final TaintedObjects delegate;
  private final boolean debug;
  private final IastContext ctx;

  protected TaintedObjectsWithTelemetry(
      final boolean debug, final TaintedObjects delegate, final IastContext ctx) {
    this.delegate = delegate;
    this.debug = debug;
    this.ctx = ctx;
  }

  @Nullable
  @Override
  public TaintedObject taint(@Nonnull Object obj, @Nonnull Range[] ranges) {
    final TaintedObject result = delegate.taint(obj, ranges);
    if (debug) {
      IastMetricCollector.add(EXECUTED_TAINTED, 1, ctx);
    }
    return result;
  }

  @Nullable
  @Override
  public TaintedObject get(@Nonnull Object obj) {
    return delegate.get(obj);
  }

  @Override
  public void clear() {
    try {
      IastMetricCollector.add(REQUEST_TAINTED, count(), ctx);
    } finally {
      delegate.clear();
    }
  }

  @Nonnull
  @Override
  public Iterator<TaintedObject> iterator() {
    return delegate.iterator();
  }

  @Override
  public int count() {
    return delegate.count();
  }

  @Override
  public TaintedObjects unwrap() {
    return delegate;
  }
}
