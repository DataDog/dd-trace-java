package com.datadog.iast.sink;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import datadog.trace.api.iast.sink.NoHttpOnlyCookieModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import javax.annotation.Nonnull;

public class NoHttpOnlyCookieModuleImpl extends SinkModuleBase implements NoHttpOnlyCookieModule {

  @Override
  public void onCookie(
      @Nonnull final String name,
      final String value,
      final boolean isSecure,
      final boolean isHttpOnly,
      final String sameSite) {
    if (!isHttpOnly) {
      final AgentSpan span = AgentTracer.activeSpan();
      if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
        return;
      }
      report(span, VulnerabilityType.NO_HTTP_ONLY_COOKIE, new Evidence(name));
    }
  }
}
