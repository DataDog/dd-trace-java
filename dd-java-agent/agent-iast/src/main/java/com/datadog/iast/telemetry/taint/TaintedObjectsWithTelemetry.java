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
import javax.annotation.Nonnull;

public abstract class TaintedObjectsWithTelemetry {

  private TaintedObjectsWithTelemetry() {}

  public static TaintedObjects build(
      final Verbosity verbosity, final TaintedObjects taintedObjects) {
    if (verbosity.isDebugEnabled()) {
      return new TaintedObjectsDebug(taintedObjects);
    }
    if (verbosity.isInformationEnabled()) {
      return new TaintedObjectsInformation(taintedObjects);
    }
    return taintedObjects;
  }

  private static class TaintedObjectsInformation implements TaintedObjects {
    protected final TaintedObjects delegate;
    private volatile RequestContext ctx;

    public TaintedObjectsInformation(final TaintedObjects delegate) {
      this.delegate = delegate;
    }

    @Override
    public TaintedObject taintInputString(@Nonnull String obj, @Nonnull Source source) {
      return delegate.taintInputString(obj, source);
    }

    @Override
    public TaintedObject taint(@Nonnull Object obj, @Nonnull Range[] ranges) {
      return delegate.taint(obj, ranges);
    }

    @Override
    public TaintedObject taintInputObject(@Nonnull Object obj, @Nonnull Source source) {
      return delegate.taintInputObject(obj, source);
    }

    @Override
    public TaintedObject get(@Nonnull Object obj) {
      return delegate.get(obj);
    }

    @Override
    public void release() {
      delegate.release();
      final RequestContext ctx = getRequestContext();
      if (delegate.isFlat()) {
        IastMetricCollector.add(TAINTED_FLAT_MODE, 1, ctx);
      } else {
        IastMetricCollector.add(REQUEST_TAINTED, delegate.getEstimatedSize(), ctx);
      }
    }

    @Override
    public long getEstimatedSize() {
      return delegate.getEstimatedSize();
    }

    @Override
    public boolean isFlat() {
      return delegate.isFlat();
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

  private static class TaintedObjectsDebug extends TaintedObjectsInformation {

    public TaintedObjectsDebug(final TaintedObjects delegate) {
      super(delegate);
    }

    @Override
    public TaintedObject taintInputString(@Nonnull String obj, @Nonnull Source source) {
      final TaintedObject result = delegate.taintInputString(obj, source);
      IastMetricCollector.add(EXECUTED_TAINTED, 1, getRequestContext());
      return result;
    }

    @Override
    public TaintedObject taintInputObject(@Nonnull Object obj, @Nonnull Source source) {
      final TaintedObject result = delegate.taintInputObject(obj, source);
      IastMetricCollector.add(EXECUTED_TAINTED, 1, getRequestContext());
      return result;
    }

    @Override
    public TaintedObject taint(@Nonnull Object obj, @Nonnull Range[] ranges) {
      final TaintedObject result = delegate.taint(obj, ranges);
      IastMetricCollector.add(EXECUTED_TAINTED, 1, getRequestContext());
      return result;
    }
  }
}
