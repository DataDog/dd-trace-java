package com.datadog.iast.sink;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import com.datadog.iast.util.CookieSecurityDetails;
import com.datadog.iast.util.CookieSecurityParser;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.HttpResponseHeaderModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nonnull;

public class HttpResponseHeaderModuleImpl extends SinkModuleBase
    implements HttpResponseHeaderModule {
  private static final String SET_COOKIE_HEADER = "Set-Cookie";

  @Override
  public void onHeader(@Nonnull final String name, final String value) {
    if (SET_COOKIE_HEADER.equalsIgnoreCase(name)) {
      CookieSecurityParser cookieSecurityInfo = new CookieSecurityParser(value);
      onCookies(cookieSecurityInfo.getBadCookies());
    }
    if (null != InstrumentationBridge.UNVALIDATED_REDIRECT) {
      InstrumentationBridge.UNVALIDATED_REDIRECT.onHeader(name, value);
    }
  }

  private void onCookies(List<CookieSecurityDetails> vulnerableCookies) {
    if (vulnerableCookies.size() > 0) {
      final AgentSpan span = AgentTracer.activeSpan();
      if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
        return;
      }
      for (CookieSecurityDetails cookie : vulnerableCookies) {
        Location location = Location.forSpanAndStack(spanId(span), getCurrentStackTrace());
        Evidence evidence = new Evidence(cookie.getCookieName());
        if (!cookie.isSecure() && null != InstrumentationBridge.INSECURE_COOKIE) {
          reporter.report(
              span, new Vulnerability(VulnerabilityType.INSECURE_COOKIE, location, evidence));
        }
        if (!cookie.isHttpOnly() && null != InstrumentationBridge.NO_HTTPONLY_COOKIE) {
          reporter.report(
              span, new Vulnerability(VulnerabilityType.NO_HTTPONLY_COOKIE, location, evidence));
        }
        if (!cookie.isSameSiteStrict() && null != InstrumentationBridge.NO_SAMESITE_COOKIE) {
          reporter.report(
              span, new Vulnerability(VulnerabilityType.NO_SAMESITE_COOKIE, location, evidence));
        }
      }
    }
  }

  @Override
  public void onCookie(
      @Nonnull final String name,
      final boolean isSecure,
      final boolean isHttpOnly,
      final boolean isSameSiteStrict) {
    if ((!isSecure && null != InstrumentationBridge.INSECURE_COOKIE)
        || (!isHttpOnly && null != InstrumentationBridge.NO_HTTPONLY_COOKIE)
        || (!isSameSiteStrict && null != InstrumentationBridge.NO_SAMESITE_COOKIE)) {
      CookieSecurityDetails details =
          new CookieSecurityDetails(name, isSecure, isHttpOnly, isSameSiteStrict);
      onCookies(Arrays.asList(details));
    }
  }
}
