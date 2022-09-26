package com.datadog.iast;

import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityBatch;
import datadog.trace.api.DDTags;
import datadog.trace.api.TraceSegment;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

/** Reports IAST vulnerabilities. */
public class Reporter {

  public Reporter() {}

  public void report(final AgentSpan span, final Vulnerability vulnerability) {
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
      final TraceSegment segment = reqCtx.getTraceSegment();
      segment.setDataTop("iast", batch);
      // Once we have added a vulnerability, try to override sampling and keep the trace.
      // TODO: We need to check if we can have an API with more fine-grained semantics on why traces
      // are kept.
      segment.setTagTop(DDTags.MANUAL_KEEP, true);
    }
  }
}
