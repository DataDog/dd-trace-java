package com.datadog.iast.telemetry.taint;

import static datadog.trace.api.iast.telemetry.IastMetric.EXECUTED_TAINTED;
import static datadog.trace.api.iast.telemetry.IastMetric.REQUEST_TAINTED;
import static datadog.trace.api.iast.telemetry.IastMetric.TAINTED_FLAT_MODE;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import com.datadog.iast.taint.TaintedObject;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.telemetry.IastMetricCollector;
import datadog.trace.api.iast.telemetry.Verbosity;
import javax.annotation.Nonnull;

public class TaintedObjectsWithTelemetry implements TaintedObjects {

  public static TaintedObjects build(
      final Verbosity verbosity, final TaintedObjects taintedObjects) {
    if (verbosity.isInformationEnabled()) {
      return new TaintedObjectsWithTelemetry(verbosity.isDebugEnabled(), taintedObjects);
    }
    return taintedObjects;
  }

  private final TaintedObjects delegate;
  private final boolean debug;
  private IastRequestContext ctx;

  protected TaintedObjectsWithTelemetry(final boolean debug, final TaintedObjects delegate) {
    this.delegate = delegate;
    this.debug = debug;
  }

  /**
   * {@link IastRequestContext} depends on {@link TaintedObjects} so it cannot be initialized via
   * ctor
   */
  public void initContext(final IastRequestContext ctx) {
    this.ctx = ctx;
  }

  @Override
  public TaintedObject taintInputString(
      @Nonnull String obj, @Nonnull Source source, final int mark) {
    final TaintedObject result = delegate.taintInputString(obj, source, mark);
    if (debug) {
      IastMetricCollector.add(EXECUTED_TAINTED, 1, ctx);
    }
    return result;
  }

  @Override
  public TaintedObject taint(@Nonnull Object obj, @Nonnull Range[] ranges) {
    final TaintedObject result = delegate.taint(obj, ranges);
    if (debug) {
      IastMetricCollector.add(EXECUTED_TAINTED, 1, ctx);
    }
    return result;
  }

  @Override
  public TaintedObject taintInputObject(
      @Nonnull Object obj, @Nonnull Source source, final int mark) {
    final TaintedObject result = delegate.taintInputObject(obj, source, mark);
    if (debug) {
      IastMetricCollector.add(EXECUTED_TAINTED, 1, ctx);
    }
    return result;
  }

  @Override
  public TaintedObject get(@Nonnull Object obj) {
    return delegate.get(obj);
  }

  @Override
  public int release() {
    final int count = delegate.release();
    if (delegate.isFlat()) {
      IastMetricCollector.add(TAINTED_FLAT_MODE, 1, ctx);
    }
    IastMetricCollector.add(REQUEST_TAINTED, count, ctx);
    return count;
  }

  @Override
  public boolean isFlat() {
    return delegate.isFlat();
  }
}
