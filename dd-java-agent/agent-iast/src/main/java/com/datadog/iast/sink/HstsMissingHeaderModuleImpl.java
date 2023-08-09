package com.datadog.iast.sink;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.iast.sink.HstsMissingHeaderModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.net.HttpURLConnection;
import java.net.URL;
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

  @Override
  public void onRequestEnd(final Object iastRequestContextObject, final IGSpanInfo igSpanInfo) {

    final IastRequestContext iastRequestContext = (IastRequestContext) iastRequestContextObject;

    if (!iastRequestContext.getHstsHeaderIsSet()) {
      try {
        Map<String, Object> tags = igSpanInfo.getTags();
        String urlString = (String) tags.get("http.url");
        if (null != tags.get("http.status_code")) {
          Integer httpStatus = (Integer) tags.get("http.status_code");
          if (httpStatus == HttpURLConnection.HTTP_MOVED_PERM
              || httpStatus == HttpURLConnection.HTTP_MOVED_TEMP
              || httpStatus == HttpURLConnection.HTTP_NOT_MODIFIED
              || httpStatus == HttpURLConnection.HTTP_NOT_FOUND
              || httpStatus == HttpURLConnection.HTTP_GONE
              || httpStatus == HttpURLConnection.HTTP_INTERNAL_ERROR
              || httpStatus == 307) {
            return;
          }
        }
        URL url = new URL(urlString);
        if (null != iastRequestContext.getContentType()
            && (iastRequestContext.getContentType().contains("text/html")
                || iastRequestContext.getContentType().contains("application/xhtml+xml"))
            && (url.getProtocol().equals("https")
                || iastRequestContext.getXForwardedProtoIsHtttps())) {
          final AgentSpan span = AgentTracer.activeSpan();
          if (overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
            reporter.report(
                span, new Vulnerability(VulnerabilityType.HSTS_HEADER_MISSING, null, null));
          }
        }
      } catch (Throwable e) {
        LOGGER.debug("Exception while checking for missing HSTS headers vulnerability", e);
      }
    }
  }

  @Override
  public void onHstsHeader(String value) {
    if (isValidMaxAge(value)) {
      final AgentSpan span = AgentTracer.activeSpan();
      final IastRequestContext ctx = IastRequestContext.get(span);
      if (ctx == null) {
        return;
      } else {
        ctx.setHstsHeaderIsSet();
      }
    }
  }

  public static boolean isValidMaxAge(@Nullable final String value) {
    if (value == null) {
      return false;
    }
    final Matcher matcher = MAX_AGE.matcher(value);
    if (!matcher.find()) {
      return false;
    }
    return Integer.parseInt(matcher.group(1)) > 0;
  }
}
