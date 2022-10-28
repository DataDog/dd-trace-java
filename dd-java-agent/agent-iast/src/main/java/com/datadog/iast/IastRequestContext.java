package com.datadog.iast;

import com.datadog.iast.model.VulnerabilityBatch;
import com.datadog.iast.overhead.OverheadContext;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

public class IastRequestContext {

  private final VulnerabilityBatch vulnerabilityBatch;
  private final AtomicBoolean spanDataIsSet;
  private final TaintedObjects taintedObjects;
  private final OverheadContext overheadContext;

  public IastRequestContext() {
    this.vulnerabilityBatch = new VulnerabilityBatch();
    this.spanDataIsSet = new AtomicBoolean(false);
    this.overheadContext = new OverheadContext();
    this.taintedObjects = new TaintedObjects();
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
