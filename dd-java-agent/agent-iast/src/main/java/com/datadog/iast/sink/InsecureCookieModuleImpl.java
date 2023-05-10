package com.datadog.iast.sink;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import datadog.trace.api.Config;
import datadog.trace.api.iast.sink.InsecureCookieModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.net.HttpCookie;
import java.util.List;
import javax.annotation.Nonnull;

public class InsecureCookieModuleImpl extends SinkModuleBase implements InsecureCookieModule {

  private Config config;

  @Override
  public void registerDependencies(@Nonnull Dependencies dependencies) {
    super.registerDependencies(dependencies);
    config = dependencies.getConfig();
  }

  @Override
  public void onCookie(String cookieName, boolean secure) {
    if (!secure && cookieName != null && cookieName.length() > 0) {
      final AgentSpan span = AgentTracer.activeSpan();
      if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
        return;
      }
      report(span, VulnerabilityType.INSECURE_COOKIE, new Evidence(cookieName));
    }
  }

  @Override
  public void onCookieHeader(String value) {
    if (null == value) {
      return;
    }
    try {
      List<HttpCookie> cookies = HttpCookie.parse(value);
      for (HttpCookie cookie : cookies) {
        if (!cookie.getSecure()) {
          final AgentSpan span = AgentTracer.activeSpan();
          if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
            return;
          }
          report(span, VulnerabilityType.INSECURE_COOKIE, new Evidence(cookie.getName()));
          return;
        }
      }
    } catch (IllegalArgumentException e) {
      return;
    }
  }
}
