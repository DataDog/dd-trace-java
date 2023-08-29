package com.datadog.iast.sink;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.iast.sink.XContentTypeModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class XContentTypeModuleImpl extends SinkModuleBase implements XContentTypeModule {
  private static final Logger LOGGER = LoggerFactory.getLogger(XContentTypeModuleImpl.class);

  @Override
  public void onRequestEnd(final Object iastRequestContextObject, final IGSpanInfo igSpanInfo) {
    try {

      final IastRequestContext iastRequestContext = (IastRequestContext) iastRequestContextObject;

      if (!isNoSniffContentOptions(iastRequestContext.getxContentTypeOptions())) {
        if (!isHtmlResponse(iastRequestContext.getContentType())) {
          return;
        }
        Map<String, Object> tags = igSpanInfo.getTags();
        if (isIgnorableResponseCode((Integer) tags.get("http.status_code"))) {
          return;
        }
        final AgentSpan span = AgentTracer.activeSpan();
        if (overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
          reporter.report(
              span, new Vulnerability(VulnerabilityType.XCONTENTTYPE_HEADER_MISSING, null, null));
        }
      }
    } catch (Throwable e) {
      LOGGER.debug("Exception while checking for missing X Content type optios header", e);
    }
  }

  static boolean isNoSniffContentOptions(@Nullable final String value) {
    if (value == null) {
      return false;
    }
    return value.toLowerCase(Locale.ROOT).contains("nosniff");
  }
}
