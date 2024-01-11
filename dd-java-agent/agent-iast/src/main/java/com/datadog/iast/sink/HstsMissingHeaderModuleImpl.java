package com.datadog.iast.sink;

import com.datadog.iast.Dependencies;
import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.sink.HstsMissingHeaderModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HstsMissingHeaderModuleImpl extends SinkModuleBase implements HstsMissingHeaderModule {
  private static final Pattern MAX_AGE =
      Pattern.compile("max-age=(\\d+)", Pattern.CASE_INSENSITIVE);

  private static final Logger LOGGER = LoggerFactory.getLogger(HstsMissingHeaderModuleImpl.class);

  public HstsMissingHeaderModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onRequestEnd(final IastContext ctx, final IGSpanInfo igSpanInfo) {
    if (!(ctx instanceof IastRequestContext)) {
      return;
    }
    final IastRequestContext iastRequestContext = (IastRequestContext) ctx;

    if (!isValidMaxAge(iastRequestContext.getStrictTransportSecurity())) {
      try {
        Map<String, Object> tags = igSpanInfo.getTags();
        String urlString = (String) tags.get("http.url");
        Integer httpStatus = (Integer) tags.get("http.status_code");
        if (isIgnorableResponseCode(httpStatus)) {
          return;
        }
        if (!isHtmlResponse(iastRequestContext.getContentType())) {
          return;
        }
        if (!isHttps(urlString, iastRequestContext.getxForwardedProto())) {
          return;
        }
        final AgentSpan span = AgentTracer.activeSpan();
        if (overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
          reporter.report(
              span,
              new Vulnerability(
                  VulnerabilityType.HSTS_HEADER_MISSING, Location.forSpan(span), null));
        }
      } catch (Throwable e) {
        LOGGER.debug("Exception while checking for missing HSTS headers vulnerability", e);
      }
    }
  }

  static boolean isValidMaxAge(@Nullable final String value) {
    if (value == null) {
      return false;
    }
    final Matcher matcher = MAX_AGE.matcher(value);
    if (!matcher.find()) {
      return false;
    }
    return Integer.parseInt(matcher.group(1)) > 0;
  }

  static boolean isHttps(@Nullable final String urlString, @Nullable final String forwardedFor) {
    if (urlString == null) {
      return false;
    }
    if (urlString.toLowerCase(Locale.ROOT).startsWith("https://")) {
      return true;
    }
    if (forwardedFor == null) {
      return false;
    }
    return forwardedFor.toLowerCase(Locale.ROOT).contains("https");
  }
}
