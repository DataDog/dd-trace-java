package com.datadog.iast.sink;

import com.datadog.iast.Dependencies;
import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.sink.SessionRewritingModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Map;
import javax.annotation.Nullable;

public class SessionRewritingModuleImpl extends SinkModuleBase implements SessionRewritingModule {

  private static final String JSESSIONID = ";jsessionid";

  public SessionRewritingModuleImpl(Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onRequestEnd(IastContext ctx, IGSpanInfo igSpanInfo) {
    if (!(ctx instanceof IastRequestContext)) {
      return;
    }
    final IastRequestContext iastRequestContext = (IastRequestContext) ctx;
    Map<String, Object> tags = igSpanInfo.getTags();
    String url = (String) tags.get("http.url");
    if (url == null || !url.contains(JSESSIONID)) {
      return;
    }
    if (isIgnorableResponseCode((Integer) tags.get("http.status_code"))) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
      return;
    }
    String reason = "URL: " + url;
    String referrer = iastRequestContext.getReferrer();
    if (referrer != null && !referrer.isEmpty()) {
      reason = reason + " Referrer: " + referrer;
    }
    final Evidence result = new Evidence(reason);
    report(span, VulnerabilityType.SESSION_REWRITING, result);
  }

  @Override
  public boolean isIgnorableResponseCode(@Nullable Integer httpStatus) {
    return httpStatus != null && httpStatus >= 400;
  }
}
