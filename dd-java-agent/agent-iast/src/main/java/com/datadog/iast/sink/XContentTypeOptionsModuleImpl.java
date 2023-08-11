package com.datadog.iast.sink;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.iast.sink.XContentTypeOptionsModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.net.HttpURLConnection;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class XContentTypeOptionsModuleImpl extends SinkModuleBase
    implements XContentTypeOptionsModule {
  private static final Logger LOGGER = LoggerFactory.getLogger(XContentTypeOptionsModuleImpl.class);

  @Override
  public void onRequestEnd(final Object iastRequestContextObject, final IGSpanInfo igSpanInfo) {
    try {

      final IastRequestContext iastRequestContext = (IastRequestContext) iastRequestContextObject;

      if (!iastRequestContext.getContentTypeOptionsIsNoSniff()) {
        if (null != iastRequestContext.getContentType()
                && (iastRequestContext.getContentType().contains("text/html"))
            || (iastRequestContext.getContentType().contains("application/xhtml+xml"))) {
          Map<String, Object> tags = igSpanInfo.getTags();
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
          final AgentSpan span = AgentTracer.activeSpan();
          if (overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
            reporter.report(
                span,
                new Vulnerability(
                    VulnerabilityType.XCONTENTTYPEOPTIONS_HEADER_MISSING, null, null));
          }
        }
      }
    } catch (Throwable e) {
      LOGGER.debug("Exception while checking for missing X Content type optios header", e);
    }
  }
}
