package datadog.trace.instrumentation.servlet5;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JakartaServletBlockingHelper {
  private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
  private static final Logger log = LoggerFactory.getLogger(JakartaServletBlockingHelper.class);

  public static void commitBlockingResponse(
      TraceSegment segment,
      HttpServletRequest httpServletRequest,
      HttpServletResponse resp,
      int statusCode_,
      BlockingContentType bct,
      Map<String, String> extraHeaders) {
    int statusCode = BlockingActionHelper.getHttpCode(statusCode_);
    if (!start(resp, statusCode)) {
      return;
    }

    for (Map.Entry<String, String> h : extraHeaders.entrySet()) {
      resp.setHeader(h.getKey(), h.getValue());
    }

    byte[] template;
    if (bct != BlockingContentType.NONE) {
      String acceptHeader = httpServletRequest.getHeader("Accept");
      BlockingActionHelper.TemplateType type =
          BlockingActionHelper.determineTemplateType(bct, acceptHeader);
      template = BlockingActionHelper.getTemplate(type);
      String contentType = BlockingActionHelper.getContentType(type);

      resp.setHeader("Content-length", Integer.toString(template.length));
      resp.setHeader("Content-type", contentType);
    } else {
      template = EMPTY_BYTE_ARRAY;
    }
    segment.effectivelyBlocked();

    try {
      OutputStream os = resp.getOutputStream();
      os.write(template);
      os.close();
    } catch (IOException e) {
      log.warn("Error sending error page", e);
    }
  }

  public static void commitBlockingResponse(
      TraceSegment segment,
      HttpServletRequest httpServletRequest,
      HttpServletResponse resp,
      Flow.Action.RequestBlockingAction rba) {

    commitBlockingResponse(
        segment,
        httpServletRequest,
        resp,
        rba.getStatusCode(),
        rba.getBlockingContentType(),
        rba.getExtraHeaders());
  }

  private static boolean start(HttpServletResponse resp, int statusCode) {
    if (resp.isCommitted()) {
      log.warn("response already committed, we can't change it");
      return false;
    }

    log.debug("Committing blocking response");

    resp.reset();
    resp.setStatus(statusCode);

    return true;
  }
}
