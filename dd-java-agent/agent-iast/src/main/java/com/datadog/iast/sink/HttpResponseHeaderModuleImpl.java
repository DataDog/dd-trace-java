package com.datadog.iast.sink;

import static com.datadog.iast.util.HttpHeader.Values.SET_COOKIE;
import static java.util.Collections.singletonList;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import com.datadog.iast.util.CookieSecurityParser;
import com.datadog.iast.util.HttpHeader;
import com.datadog.iast.util.HttpHeader.ContextAwareHeader;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.HttpCookieModule;
import datadog.trace.api.iast.sink.HttpResponseHeaderModule;
import datadog.trace.api.iast.util.Cookie;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

public class HttpResponseHeaderModuleImpl extends SinkModuleBase
    implements HttpResponseHeaderModule {

  @Override
  public void onHeader(@Nonnull final String name, final String value) {
    final HttpHeader header = HttpHeader.from(name);
    if (header != null) {
      if (header instanceof ContextAwareHeader) {
        final AgentSpan span = AgentTracer.activeSpan();
        final IastRequestContext ctx = IastRequestContext.get(span);
        if (ctx != null) {
          ((ContextAwareHeader) header).onHeader(ctx, value);
        }
      }
      if (header == SET_COOKIE) {
        onCookies(CookieSecurityParser.parse(value));
      }
      if (null != InstrumentationBridge.UNVALIDATED_REDIRECT) {
        InstrumentationBridge.UNVALIDATED_REDIRECT.onHeader(name, value);
      }
    }
  }

  @Override
  public void onCookie(@Nonnull final Cookie cookie) {
    onCookies(singletonList(cookie));
  }

  private void onCookies(final List<Cookie> cookies) {
    final Map<VulnerabilityType, Cookie> vulnerable = findVulnerableCookies(cookies);
    if (vulnerable.isEmpty()) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
      return;
    }
    final Location location = Location.forSpanAndStack(spanId(span), getCurrentStackTrace());
    for (final Map.Entry<VulnerabilityType, Cookie> entry : vulnerable.entrySet()) {
      final Cookie cookie = entry.getValue();
      final Evidence evidence = new Evidence(cookie.getCookieName());
      reporter.report(span, new Vulnerability(entry.getKey(), location, evidence));
    }
  }

  private static Map<VulnerabilityType, Cookie> findVulnerableCookies(final List<Cookie> cookies) {
    final List<HttpCookieModule<VulnerabilityType>> modules = httpCookieModules();
    final Map<VulnerabilityType, Cookie> found = new HashMap<>(modules.size());
    for (final Cookie cookie : cookies) {
      for (int i = modules.size() - 1; i >= 0; i--) {
        final HttpCookieModule<VulnerabilityType> module = modules.get(i);
        if (module.isVulnerable(cookie)) {
          found.put(module.getType(), cookie);
          modules.remove(i); // remove module as we already found a vulnerability
        }
      }
      if (modules.isEmpty()) {
        break;
      }
    }
    return found;
  }

  @SuppressWarnings("unchecked")
  private static List<HttpCookieModule<VulnerabilityType>> httpCookieModules() {
    final List<HttpCookieModule<VulnerabilityType>> modules = new ArrayList<>();
    if (InstrumentationBridge.NO_HTTPONLY_COOKIE != null) {
      modules.add((HttpCookieModule<VulnerabilityType>) InstrumentationBridge.NO_HTTPONLY_COOKIE);
    }
    if (InstrumentationBridge.INSECURE_COOKIE != null) {
      modules.add((HttpCookieModule<VulnerabilityType>) InstrumentationBridge.INSECURE_COOKIE);
    }
    if (InstrumentationBridge.NO_SAMESITE_COOKIE != null) {
      modules.add((HttpCookieModule<VulnerabilityType>) InstrumentationBridge.NO_SAMESITE_COOKIE);
    }
    return modules;
  }
}
