package com.datadog.iast;

import com.datadog.iast.model.VulnerabilityBatch;
import com.datadog.iast.overhead.OverheadContext;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.telemetry.IastMetricCollector;
import datadog.trace.api.iast.telemetry.IastMetricCollector.HasMetricCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

public class IastRequestContext implements HasMetricCollector {

  private final VulnerabilityBatch vulnerabilityBatch;
  private final AtomicBoolean spanDataIsSet;
  private final TaintedObjects taintedObjects;
  private final OverheadContext overheadContext;
  private final IastMetricCollector collector;

  public IastRequestContext() {
    this(TaintedObjects.acquire(), null);
  }

  public IastRequestContext(final TaintedObjects taintedObjects) {
    this(taintedObjects, null);
  }

  public IastRequestContext(
      final TaintedObjects taintedObjects, final IastMetricCollector collector) {
    this.vulnerabilityBatch = new VulnerabilityBatch();
    this.spanDataIsSet = new AtomicBoolean(false);
    this.overheadContext = new OverheadContext();
    this.taintedObjects = taintedObjects;
    this.collector = collector;
  }

  public VulnerabilityBatch getVulnerabilityBatch() {
    return vulnerabilityBatch;
  }

  public boolean getAndSetSpanDataIsSet() {
    return spanDataIsSet.getAndSet(true);
  }

  public OverheadContext getOverheadContext() {
    return overheadContext;
  }

  public TaintedObjects getTaintedObjects() {
    return taintedObjects;
  }

  @Override
  @Nullable
  public IastMetricCollector getMetricCollector() {
    return collector;
  }

  @Nullable
  public static IastRequestContext get() {
    return get(AgentTracer.activeSpan());
  }

  @Nullable
  public static IastRequestContext get(final AgentSpan span) {
    if (span == null) {
      return null;
    }
    return get(span.getRequestContext());
  }

  @Nullable
  public static IastRequestContext get(final RequestContext reqCtx) {
    if (reqCtx == null) {
      return null;
    }
    return reqCtx.getData(RequestContextSlot.IAST);
  }
}
