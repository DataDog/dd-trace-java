package com.datadog.iast;

import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityBatch;
import com.datadog.iast.model.json.VulnerabilityEncoding;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reports IAST vulnerabilities. */
public final class Reporter {

  private static final String IAST_TAG = "_dd.iast.json";

  private static final Logger log = LoggerFactory.getLogger(Reporter.class);

  private Reporter() {}

  public static void report(final AgentSpan span, final Vulnerability vulnerability) {
    final IastRequestContext ctx = getRequestContext(span);
    if (ctx == null) {
      return;
    }
    ctx.addVulnerability(vulnerability);
  }

  public static void finalizeReports(final RequestContext<Object> requestContext) {
    if (requestContext == null) {
      return;
    }
    final IastRequestContext ctx = (IastRequestContext) requestContext.getIastContext();
    if (ctx == null) {
      return;
    }
    final List<Vulnerability> vulns = ctx.getVulnerabilities();
    if (vulns == null || vulns.isEmpty()) {
      return;
    }
    requestContext
        .getTraceSegment()
        .setTagTop(
            IAST_TAG, VulnerabilityEncoding.toJson(VulnerabilityBatch.forVulnerabilities(vulns)));
  }

  private static IastRequestContext getRequestContext(final AgentSpan span) {
    if (span == null) {
      return null;
    }
    final RequestContext<Object> reqCtx = span.getRequestContext();
    if (reqCtx == null) {
      return null;
    }
    final Object data = reqCtx.getIastContext();
    if (!(data instanceof IastRequestContext)) {
      return null;
    }
    return (IastRequestContext) data;
  }
}
