package com.datadog.iast.telemetry.taint;

import static datadog.trace.api.iast.telemetry.IastMetric.EXECUTED_TAINTED;
import static datadog.trace.api.iast.telemetry.IastMetric.REQUEST_TAINTED;
import static datadog.trace.api.iast.telemetry.IastMetric.TAINTED_FLAT_MODE;

import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import com.datadog.iast.taint.TaintedObject;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.iast.telemetry.IastMetricCollector;
import datadog.trace.api.iast.telemetry.Verbosity;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Iterator;
import javax.annotation.Nonnull;

public class TaintedObjectsWithTelemetry implements TaintedObjects {

  /**
   * If the estimated size of the tainted objects is lower than this threshold we will count instead
   */
  private static final int COUNT_THRESHOLD = 1024;

  public static TaintedObjects build(
      final Verbosity verbosity, final TaintedObjects taintedObjects) {
    if (verbosity.isInformationEnabled()) {
      return new TaintedObjectsWithTelemetry(verbosity.isDebugEnabled(), taintedObjects);
    }
    return taintedObjects;
  }

  private final TaintedObjects delegate;
  private final boolean debug;
  private volatile RequestContext ctx;

  protected TaintedObjectsWithTelemetry(final boolean debug, final TaintedObjects delegate) {
    this.delegate = delegate;
    this.debug = debug;
  }

  @Override
  public TaintedObject taintInputString(
      @Nonnull String obj, @Nonnull Source source, final int mark) {
    final TaintedObject result = delegate.taintInputString(obj, source, mark);
    if (debug) {
      IastMetricCollector.add(EXECUTED_TAINTED, 1, getRequestContext());
    }
    return result;
  }

  @Override
  public TaintedObject taint(@Nonnull Object obj, @Nonnull Range[] ranges) {
    final TaintedObject result = delegate.taint(obj, ranges);
    if (debug) {
      IastMetricCollector.add(EXECUTED_TAINTED, 1, getRequestContext());
    }
    return result;
  }

  @Override
  public TaintedObject taintInputObject(
      @Nonnull Object obj, @Nonnull Source source, final int mark) {
    final TaintedObject result = delegate.taintInputObject(obj, source, mark);
    if (debug) {
      IastMetricCollector.add(EXECUTED_TAINTED, 1, getRequestContext());
    }
    return result;
  }

  @Override
  public TaintedObject get(@Nonnull Object obj) {
    return delegate.get(obj);
  }

  @Override
  public void release() {
    try {
      final RequestContext reqCtx = getRequestContext();
      if (delegate.isFlat()) {
        IastMetricCollector.add(TAINTED_FLAT_MODE, 1, reqCtx);
      }
      IastMetricCollector.add(REQUEST_TAINTED, computeSize(), reqCtx);
    } finally {
      delegate.release();
    }
  }

  @Override
  public Iterator<TaintedObject> iterator() {
    return delegate.iterator();
  }

  @Override
  public int count() {
    return delegate.count();
  }

  @Override
  public int getEstimatedSize() {
    return delegate.getEstimatedSize();
  }

  @Override
  public boolean isFlat() {
    return delegate.isFlat();
  }

  private int computeSize() {
    int size = getEstimatedSize();
    return size > COUNT_THRESHOLD ? size : count();
  }

  /**
   * A {@link TaintedObjects} data structure is always linked to a {@link RequestContext} so it's
   * actually OK to cache the result.
   */
  protected RequestContext getRequestContext() {
    if (ctx == null) {
      final AgentSpan span = AgentTracer.activeSpan();
      ctx = span == null ? null : span.getRequestContext();
    }
    return ctx;
  }
}
