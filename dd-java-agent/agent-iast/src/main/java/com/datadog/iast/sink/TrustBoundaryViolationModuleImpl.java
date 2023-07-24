package com.datadog.iast.sink;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.sink.TrustBoundaryViolationModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import javax.annotation.Nonnull;

public class TrustBoundaryViolationModuleImpl extends SinkModuleBase
    implements TrustBoundaryViolationModule {
  @Override
  public void onSessionValue(@Nonnull String name, Object value) {
    final AgentSpan span = AgentTracer.activeSpan();
    final IastRequestContext ctx = IastRequestContext.get(span);
    if (ctx == null) {
      return;
    }
    TaintedObjects taintedObjects = ctx.getTaintedObjects();
    if (null != taintedObjects.get(name)) {
      if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
        return;
      }
      report(span, VulnerabilityType.TRUST_BOUNDARY_VIOLATION, new Evidence(name));
      return;
    }
    Object taintedObject = isDeeplyTainted(value, taintedObjects);
    if (null != taintedObject) {
      checkInjection(span, ctx, VulnerabilityType.TRUST_BOUNDARY_VIOLATION, taintedObject);
    }
  }
}
