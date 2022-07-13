package com.datadog.iast;

import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityBatch;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

/** Reports IAST vulnerabilities. */
public final class Reporter {

  private Reporter() {}

  public static void report(final AgentSpan span, final Vulnerability vulnerability) {
    if (span == null) {
      return;
    }
    final RequestContext reqCtx = span.getRequestContext();
    if (reqCtx == null) {
      return;
    }
    final IastRequestContext ctx = reqCtx.getData(RequestContextSlot.IAST);
    if (ctx == null) {
      return;
    }
    final VulnerabilityBatch batch = ctx.getVulnerabilityBatch();
    batch.add(vulnerability);
    if (!ctx.getAndSetSpanDataIsSet()) {
      reqCtx.getTraceSegment().setDataTop("iast", batch);
    }
  }
}
