package com.datadog.iast.sink;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import datadog.trace.api.Config;
import datadog.trace.api.iast.sink.NoHttpOnlyCookieModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.net.HttpCookie;
import java.util.List;
import javax.annotation.Nonnull;

public class NoHttpOnlyCookieModuleImpl extends SinkModuleBase implements NoHttpOnlyCookieModule {

  private Config config;

  @Override
  public void registerDependencies(@Nonnull Dependencies dependencies) {
    super.registerDependencies(dependencies);
    config = dependencies.getConfig();
  }

  @Override
  public void onCookies(List<HttpCookie> cookies) {
    for (HttpCookie cookie : cookies) {
      if (!cookie.isHttpOnly()) {
        final AgentSpan span = AgentTracer.activeSpan();
        if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
          return;
        }
        report(span, VulnerabilityType.NO_HTTP_ONLY_COOKIE, new Evidence(cookie.getName()));
        return;
      }
    }
  }

  @Override
  public void onCookie(String cookieName, boolean isHtmlOnly) {
    if (!isHtmlOnly) {
      final AgentSpan span = AgentTracer.activeSpan();
      if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
        return;
      }
      if (!isHtmlOnly) {
        report(span, VulnerabilityType.NO_HTTP_ONLY_COOKIE, new Evidence(cookieName));
      }
    }
  }
}
