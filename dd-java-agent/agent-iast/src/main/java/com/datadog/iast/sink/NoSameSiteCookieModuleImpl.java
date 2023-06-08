package com.datadog.iast.sink;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import com.datadog.iast.util.CookieSecurityInfo;
import datadog.trace.api.iast.sink.NoSameSiteCookieModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;

public class NoSameSiteCookieModuleImpl extends SinkModuleBase
    implements NoSameSiteCookieModule, ForCookieSecurityInfo {

  @Override
  public void onCookie(CookieSecurityInfo cookieSecurityInfo) {
    if (!cookieSecurityInfo.isSameSiteStrict()) {
      if (null == cookieSecurityInfo.getLocation()) {
        final AgentSpan span = AgentTracer.activeSpan();
        if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
          return;
        }
        Location location =
            report(
                span,
                VulnerabilityType.NO_SAMESITE_COOKIE,
                new Evidence(cookieSecurityInfo.getCookieName()));
        cookieSecurityInfo.setLocation(location);
        cookieSecurityInfo.setSpan(span);
      } else {
        reporter.report(
            cookieSecurityInfo.getSpan(),
            new Vulnerability(
                VulnerabilityType.NO_SAMESITE_COOKIE,
                cookieSecurityInfo.getLocation(),
                new Evidence(cookieSecurityInfo.getCookieName())));
      }
    }
  }
}
